//! A session-local TSF document on the GLFW window's owning STA thread.
//! Modern Windows Pinyin needs genuine queued key events. Tagged input is sent only while
//! this exact game window has foreground and keyboard focus. A window subclass consumes
//! our events, including stale session tags, before they can become game shortcuts.
#![allow(non_snake_case)]
use super::{Event, Snapshot};
use std::{
    cell::{Cell, RefCell},
    collections::VecDeque,
    rc::Rc,
    time::{Duration, Instant},
};
use windows::{
    core::*,
    Win32::{
        Foundation::*,
        Graphics::Gdi::ClientToScreen,
        System::{Com::*, Variant::*},
        UI::{Input::KeyboardAndMouse::*, TextServices::*, WindowsAndMessaging::*},
    },
};
#[link(name = "kernel32")]
extern "system" {
    fn GetCurrentThreadId() -> u32;
}
type SubclassProc = unsafe extern "system" fn(HWND, u32, WPARAM, LPARAM, usize, usize) -> LRESULT;
#[link(name = "comctl32")]
extern "system" {
    fn SetWindowSubclass(
        hwnd: HWND,
        callback: Option<SubclassProc>,
        id: usize,
        data: usize,
    ) -> BOOL;
    fn RemoveWindowSubclass(hwnd: HWND, callback: Option<SubclassProc>, id: usize) -> BOOL;
    fn DefSubclassProc(hwnd: HWND, msg: u32, w: WPARAM, l: LPARAM) -> LRESULT;
}
const SUBCLASS_ID: usize = 0x4D4D4456;
const TAG_MASK: usize = 0xFFFF0000;
const TAG_PREFIX: usize = 0x4D7A0000;
thread_local! {
 static HOST:RefCell<Option<Host>>=const{RefCell::new(None)};
 static PENDING_CLOSE:Cell<bool>=const{Cell::new(false)};
 static NEXT_TAG:Cell<usize>=const{Cell::new(0)};
 static KEY_FILTER:Cell<HHOOK>=const{Cell::new(HHOOK(0))};
 static ACTIVE_WINDOW:Cell<isize>=const{Cell::new(0)};
 static DESTROYED:Cell<bool>=const{Cell::new(false)};
 static ACTIVE_TAG:Cell<usize>=const{Cell::new(0)};
 static KEY_ACK:Cell<Option<(usize,u32)>>=const{Cell::new(None)};
 static CANCEL_TAG:Cell<usize>=const{Cell::new(0)};
}
fn finish_close() {
    HOST.with(|cell| {
        if let Ok(mut slot) = cell.try_borrow_mut() {
            if let Some(host) = slot.as_mut() {
                if CANCEL_TAG.with(Cell::get) == host.tag {
                    CANCEL_TAG.with(|c| c.set(0));
                    host.cancel_inflight = true;
                    host.status = "input_unavailable";
                }
                if let Some((tag, vk)) = KEY_ACK.with(Cell::get) {
                    if tag == host.tag
                        && host.inflight.map(|(key, _, _)| key == vk).unwrap_or(false)
                    {
                        KEY_ACK.with(|a| a.set(None));
                        host.inflight = None;
                        if host.cancel_inflight {
                            host.cancel_composition();
                            if let Ok(mut d) = host.data.try_borrow_mut() {
                                d.events.clear();
                            }
                            host.cancel_inflight = false;
                        }
                    }
                }
            } else {
                KEY_ACK.with(|a| a.set(None));
            }
        }
    });
    if DESTROYED.with(Cell::get) {
        HOST.with(|cell| {
            if let Ok(mut slot) = cell.try_borrow_mut() {
                if let Some(host) = slot.as_mut() {
                    host.inflight = None;
                    host.closing = true;
                    host.reopen = false;
                    host.next_queue.clear();
                }
                DESTROYED.with(|d| d.set(false));
                PENDING_CLOSE.with(|p| p.set(false));
            }
        });
    }
    if PENDING_CLOSE.with(Cell::get) {
        close();
    }
    let retired = HOST.with(|cell| {
        let Ok(mut slot) = cell.try_borrow_mut() else {
            return None;
        };
        if slot
            .as_ref()
            .map(|h| h.closing && h.inflight.is_none())
            .unwrap_or(false)
        {
            slot.take()
        } else {
            None
        }
    });
    if let Some(mut host) = retired {
        let owner = host.hwnd;
        let reopen = host.reopen;
        let pending = std::mem::take(&mut host.next_queue);
        drop(host);
        ACTIVE_WINDOW.with(|w| w.set(0));
        ACTIVE_TAG.with(|t| t.set(0));
        unsafe {
            CoUninitialize();
        }
        if reopen && open(owner.0 as i64) {
            HOST.with(|cell| {
                if let Ok(mut slot) = cell.try_borrow_mut() {
                    if let Some(host) = slot.as_mut() {
                        host.queue = pending;
                    }
                }
            });
        }
    }
}
pub fn open(owner: i64) -> bool {
    if owner == 0 {
        return false;
    }
    let result = HOST.with(|cell| {
        let Ok(mut slot) = cell.try_borrow_mut() else {
            return false;
        };
        if let Some(host) = slot.as_mut() {
            if host.hwnd.0 != owner as isize {
                return false;
            }
            if host.closing {
                host.reopen = true;
                host.next_queue.clear();
            }
            return true;
        }
        unsafe {
            let hwnd = HWND(owner as isize);
            let mut process = 0;
            if GetWindowThreadProcessId(hwnd, Some(&mut process)) != GetCurrentThreadId()
                || process != std::process::id()
            {
                return false;
            }
            if CoInitializeEx(None, COINIT_APARTMENTTHREADED).is_err() {
                return false;
            }
            ACTIVE_WINDOW.with(|w| w.set(hwnd.0));
            match Host::new(hwnd) {
                Ok(host) => {
                    ACTIVE_TAG.with(|t| t.set(host.tag));
                    *slot = Some(host);
                    true
                }
                Err(_) => {
                    ACTIVE_WINDOW.with(|w| w.set(0));
                    ACTIVE_TAG.with(|t| t.set(0));
                    CoUninitialize();
                    false
                }
            }
        }
    });
    finish_close();
    result
}
pub fn close() {
    PENDING_CLOSE.with(|p| p.set(true));
    let removed = HOST.with(|cell| {
        let Ok(mut slot) = cell.try_borrow_mut() else {
            return None;
        };
        PENDING_CLOSE.with(|p| p.set(false));
        if let Some(host) = slot.as_mut() {
            host.queue.clear();
            host.next_queue.clear();
            host.reopen = false;
            if host.inflight.is_some() {
                host.closing = true;
                unsafe {
                    let _ = host.set_open(false);
                }
                return None;
            }
        }
        slot.take()
    });
    if let Some(host) = removed {
        drop(host);
        ACTIVE_WINDOW.with(|w| w.set(0));
        ACTIVE_TAG.with(|t| t.set(0));
        unsafe {
            CoUninitialize();
        }
    }
}
pub fn command(kind: i32, value: i32, shift: bool) -> bool {
    let result = HOST.with(|cell| {
        let Ok(mut slot) = cell.try_borrow_mut() else {
            return true;
        };
        let Some(host) = slot.as_mut() else {
            return false;
        };
        let command = match kind {
            0 if allowed_key(value as u32) => Command::Key(value as u32, shift),
            1 => Command::Enable(value != 0),
            2 if value >= 0 => Command::Candidate(value as usize),
            3 => Command::Key(if value < 0 { 0x21 } else { 0x22 }, false),
            _ => return false,
        };
        if host.closing {
            if host.reopen && host.next_queue.len() < 128 {
                host.next_queue.push_back(command);
            }
            return true;
        }
        if host.queue.len() < 128 {
            host.queue.push_back(command);
        }
        host.status = "Windows TSF";
        host.drive();
        true
    });
    finish_close();
    result
}
pub fn text(value: String) -> bool {
    if value.len() > 8192 {
        return false;
    }
    let result = HOST.with(|cell| {
        let Ok(mut slot) = cell.try_borrow_mut() else {
            return true;
        };
        let Some(host) = slot.as_mut() else {
            return false;
        };
        if host.closing {
            if host.reopen && host.next_queue.len() < 128 {
                host.next_queue.push_back(Command::Literal(value));
            }
            return true;
        }
        if host.queue.len() < 128 {
            host.queue.push_back(Command::Literal(value));
        }
        host.status = "Windows TSF";
        host.drive();
        true
    });
    finish_close();
    result
}
pub fn poll() -> Snapshot {
    let snapshot = HOST.with(|cell| {
        let Ok(mut slot) = cell.try_borrow_mut() else {
            return Snapshot::default();
        };
        let Some(host) = slot.as_mut() else {
            return Snapshot::default();
        };
        if host.closing {
            return Snapshot {
                status: if host
                    .inflight
                    .map(|(_, _, t)| t.elapsed() > Duration::from_secs(2))
                    .unwrap_or(false)
                {
                    "input_unavailable"
                } else {
                    "initializing"
                }
                .into(),
                ..Default::default()
            };
        }
        host.drive();
        host.snapshot()
    });
    finish_close();
    snapshot
}
fn allowed_key(vk: u32) -> bool {
    matches!(vk,8|9|13|27|32..=40|46|48..=57|65..=90|0xBA..=0xC0|0xDB..=0xDE)
}
// Message filtering complements the window subclass. Modern Pinyin may consume a
// key before these hooks, so close also keeps its old document until key-up is seen.
unsafe extern "system" fn filter_input(code: i32, w: WPARAM, l: LPARAM) -> LRESULT {
    if code < 0 || w.0 != PM_REMOVE.0 as usize {
        return CallNextHookEx(HHOOK(0), code, w, l);
    }
    // Copy before calling other hooks: they may edit this MSG and reenter our callbacks.
    let original = *(l.0 as *const MSG);
    let tag = GetMessageExtraInfo().0 as usize;
    if tag & TAG_MASK != TAG_PREFIX
        || !matches!(
            original.message,
            WM_KEYDOWN
                | WM_KEYUP
                | WM_SYSKEYDOWN
                | WM_SYSKEYUP
                | WM_CHAR
                | WM_SYSCHAR
                | WM_DEADCHAR
                | WM_SYSDEADCHAR
        )
    {
        return CallNextHookEx(HHOOK(0), code, w, l);
    }
    let active = tag == ACTIVE_TAG.with(Cell::get);
    let allowed = active
        && HOST.with(|cell| {
            let Ok(slot) = cell.try_borrow() else {
                if matches!(original.message, WM_KEYDOWN | WM_SYSKEYDOWN) {
                    CANCEL_TAG.with(|c| c.set(tag));
                }
                return false;
            };
            slot.as_ref()
                .map(|host| {
                    !host.closing
                        && !host.cancel_inflight
                        && host.hwnd == original.hwnd
                        && host.focused()
                })
                .unwrap_or(false)
        });
    if !allowed {
        // Old or cancelled input must not reach even an earlier TSF hook/document.
        let message = &mut *(l.0 as *mut MSG);
        message.message = WM_NULL;
        message.wParam = WPARAM(0);
        message.lParam = LPARAM(0);
    }
    // The game may already have installed TSF's hook before our session. Give it the
    // genuine queued event first, independently of hook installation order.
    let result = CallNextHookEx(HHOOK(0), code, w, l);
    if active && matches!(original.message, WM_KEYUP | WM_SYSKEYUP) {
        KEY_ACK.with(|ack| ack.set(Some((tag, original.wParam.0 as u32))));
    }
    let remaining = *(l.0 as *const MSG);
    if allowed
        && remaining.message == original.message
        && remaining.wParam == original.wParam
        && remaining.hwnd == original.hwnd
        && matches!(original.message, WM_KEYDOWN | WM_SYSKEYDOWN)
    {
        HOST.with(|cell| {
            let Ok(mut slot) = cell.try_borrow_mut() else {
                CANCEL_TAG.with(|c| c.set(tag));
                return;
            };
            if let Some(host) = slot.as_mut() {
                if host.tag == tag && !host.closing && host.focused() {
                    if let Some((vk, shift, _)) = host.inflight {
                        if vk == original.wParam.0 as u32 {
                            host.process_key(vk, shift, original.lParam);
                        }
                    }
                } else if !host.closing {
                    host.cancel_for_focus();
                }
            }
        });
    }
    // Prevent TranslateMessage from producing untagged characters/game shortcuts.
    let message = &mut *(l.0 as *mut MSG);
    message.message = WM_NULL;
    message.wParam = WPARAM(0);
    message.lParam = LPARAM(0);
    finish_close();
    result
}
unsafe fn install_filter() -> Result<()> {
    let next = SetWindowsHookExW(
        WH_GETMESSAGE,
        Some(filter_input),
        HINSTANCE(0),
        GetCurrentThreadId(),
    )?;
    let old = KEY_FILTER.with(|h| h.replace(next));
    if old.0 != 0 {
        let _ = UnhookWindowsHookEx(old);
    }
    Ok(())
}
unsafe extern "system" fn window_input(
    hwnd: HWND,
    msg: u32,
    w: WPARAM,
    l: LPARAM,
    _id: usize,
    _data: usize,
) -> LRESULT {
    if msg == WM_NCDESTROY {
        if ACTIVE_WINDOW.with(Cell::get) == hwnd.0 {
            DESTROYED.with(|d| d.set(true));
            finish_close();
        }
        RemoveWindowSubclass(hwnd, Some(window_input), SUBCLASS_ID);
        return DefSubclassProc(hwnd, msg, w, l);
    }
    let tag = GetMessageExtraInfo().0 as usize;
    if tag & TAG_MASK == TAG_PREFIX
        && matches!(
            msg,
            WM_KEYDOWN
                | WM_KEYUP
                | WM_SYSKEYDOWN
                | WM_SYSKEYUP
                | WM_CHAR
                | WM_SYSCHAR
                | WM_DEADCHAR
                | WM_SYSDEADCHAR
        )
    {
        // The subclass intentionally survives a keyboard session: queued events from a closed
        // session must never leak into a different text field or ordinary game key bindings.
        if matches!(msg, WM_KEYUP | WM_SYSKEYUP) && tag == ACTIVE_TAG.with(Cell::get) {
            KEY_ACK.with(|ack| ack.set(Some((tag, w.0 as u32))));
        }
        let _ = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
            HOST.with(|cell| {
                let Ok(mut slot) = cell.try_borrow_mut() else {
                    return;
                };
                let Some(host) = slot.as_mut() else {
                    return;
                };
                if host.tag != tag || host.hwnd != hwnd {
                    return;
                }
                if matches!(msg, WM_KEYUP | WM_SYSKEYUP)
                    && host
                        .inflight
                        .map(|(vk, _, _)| vk == w.0 as u32)
                        .unwrap_or(false)
                {
                    if host.focused()
                        && host
                            .keys
                            .TestKeyUp(w, l)
                            .map(|v| v.as_bool())
                            .unwrap_or(false)
                    {
                        let _ = host.keys.KeyUp(w, l);
                    }
                    host.inflight = None;
                    if host.cancel_inflight {
                        host.cancel_composition();
                        if let Ok(mut d) = host.data.try_borrow_mut() {
                            d.events.clear();
                        }
                        host.cancel_inflight = false;
                    }
                    return;
                }
                if !host.focused() {
                    host.cancel_for_focus();
                    return;
                }
                if matches!(msg, WM_KEYDOWN | WM_SYSKEYDOWN) {
                    if let Some((vk, shift, _)) = host.inflight {
                        if vk == w.0 as u32 {
                            host.process_key(vk, shift, l);
                        }
                    }
                }
            })
        }));
        finish_close();
        return LRESULT(0);
    }
    DefSubclassProc(hwnd, msg, w, l)
}
struct Data {
    text: Vec<u16>,
    start: i32,
    end: i32,
    composing: bool,
    events: Vec<Event>,
}
impl Default for Data {
    fn default() -> Self {
        Self {
            text: Vec::new(),
            start: 0,
            end: 0,
            composing: false,
            events: Vec::new(),
        }
    }
}

#[implement(ITextStoreACP, ITfContextOwnerCompositionSink)]
struct Store {
    attrs: RefCell<Vec<GUID>>,
    data: Rc<RefCell<Data>>,
    sink: Rc<RefCell<Option<ITextStoreACPSink>>>,
    lock: Cell<u32>,
    queued_lock: Cell<u32>,
    hwnd: HWND,
}
impl Store {
    fn range(&self, start: i32, end: i32) -> Result<(usize, usize)> {
        let len = self
            .data
            .try_borrow()
            .map_err(|_| Error::from(E_UNEXPECTED))?
            .text
            .len();
        let end = if end == -1 { len as i32 } else { end };
        if start < 0 || end < start || end as usize > len {
            Err(TS_E_INVALIDPOS.into())
        } else {
            Ok((start as usize, end as usize))
        }
    }
    fn attrs(&self, count: u32, filter: *const GUID) -> Result<()> {
        let supported = [
            GUID_PROP_INPUTSCOPE,
            TSATTRID_Text_ReadOnly,
            TSATTRID_Text_VerticalWriting,
        ];
        let filter = if count == 0 {
            &[]
        } else {
            unsafe { std::slice::from_raw_parts(filter, count as usize) }
        };
        *self
            .attrs
            .try_borrow_mut()
            .map_err(|_| Error::from(E_UNEXPECTED))? = supported
            .into_iter()
            .filter(|g| count == 0 || filter.contains(g))
            .collect();
        Ok(())
    }
    fn writable(&self) -> Result<()> {
        if self.lock.get() & TS_LF_READWRITE.0 == TS_LF_READWRITE.0 {
            Ok(())
        } else {
            Err(TS_E_NOLOCK.into())
        }
    }
}
impl ITextStoreACP_Impl for Store {
    fn AdviseSink(&self, riid: *const GUID, punk: Option<&IUnknown>, _mask: u32) -> Result<()> {
        if unsafe { *riid } != ITextStoreACPSink::IID {
            return Err(E_NOINTERFACE.into());
        }
        let next = Some(punk.ok_or_else(|| Error::from(E_INVALIDARG))?.cast()?);
        let old = std::mem::replace(
            &mut *self
                .sink
                .try_borrow_mut()
                .map_err(|_| Error::from(E_UNEXPECTED))?,
            next,
        );
        drop(old);
        Ok(())
    }
    fn UnadviseSink(&self, _: Option<&IUnknown>) -> Result<()> {
        let old = self
            .sink
            .try_borrow_mut()
            .map_err(|_| Error::from(E_UNEXPECTED))?
            .take();
        drop(old);
        Ok(())
    }
    fn RequestLock(&self, flags: u32) -> Result<HRESULT> {
        if self.lock.get() != 0 {
            if flags & TS_LF_SYNC != 0 {
                return Ok(TS_E_SYNCHRONOUS);
            }
            self.queued_lock.set(flags);
            return Ok(TS_S_ASYNC);
        }
        let Some(sink) = self
            .sink
            .try_borrow()
            .map_err(|_| Error::from(E_UNEXPECTED))?
            .clone()
        else {
            return Err(E_UNEXPECTED.into());
        };
        let mut current = flags;
        let mut result = S_OK;
        for attempt in 0..32 {
            self.lock.set(current);
            let granted = unsafe { sink.OnLockGranted(TEXT_STORE_LOCK_FLAGS(current)) };
            self.lock.set(0);
            if attempt == 0 {
                result = granted.map(|_| S_OK).unwrap_or_else(|e| e.code());
            }
            current = self.queued_lock.replace(0);
            if current == 0 {
                break;
            }
        }
        Ok(result)
    }
    fn GetStatus(&self) -> Result<TS_STATUS> {
        Ok(TS_STATUS {
            dwDynamicFlags: 0,
            dwStaticFlags: 0,
        })
    }
    fn QueryInsert(&self, start: i32, end: i32, _: u32, a: *mut i32, b: *mut i32) -> Result<()> {
        self.range(start, end)?;
        unsafe {
            *a = start;
            *b = end;
        }
        Ok(())
    }
    fn GetSelection(
        &self,
        index: u32,
        count: u32,
        out: *mut TS_SELECTION_ACP,
        fetched: *mut u32,
    ) -> Result<()> {
        unsafe {
            *fetched = 0;
        }
        if count == 0 {
            return Ok(());
        }
        if index != 0 && index != u32::MAX {
            return Err(E_INVALIDARG.into());
        }
        let d = self
            .data
            .try_borrow()
            .map_err(|_| Error::from(E_UNEXPECTED))?;
        unsafe {
            *out = TS_SELECTION_ACP {
                acpStart: d.start,
                acpEnd: d.end,
                style: TS_SELECTIONSTYLE {
                    ase: TS_AE_END,
                    fInterimChar: BOOL(0),
                },
            };
            *fetched = 1;
        }
        Ok(())
    }
    fn SetSelection(&self, count: u32, p: *const TS_SELECTION_ACP) -> Result<()> {
        self.writable()?;
        if count != 1 {
            return Err(E_INVALIDARG.into());
        }
        let s = unsafe { *p };
        self.range(s.acpStart, s.acpEnd)?;
        let mut d = self
            .data
            .try_borrow_mut()
            .map_err(|_| Error::from(E_UNEXPECTED))?;
        d.start = s.acpStart;
        d.end = s.acpEnd;
        Ok(())
    }
    fn GetText(
        &self,
        start: i32,
        end: i32,
        plain: PWSTR,
        requested: u32,
        returned: *mut u32,
        run: *mut TS_RUNINFO,
        run_count: u32,
        run_returned: *mut u32,
        next: *mut i32,
    ) -> Result<()> {
        let (a, b) = self.range(start, end)?;
        let d = self
            .data
            .try_borrow()
            .map_err(|_| Error::from(E_UNEXPECTED))?;
        let n = if requested == 0 {
            b - a
        } else {
            (b - a).min(requested as usize)
        };
        unsafe {
            if requested > 0 && n > 0 {
                std::ptr::copy_nonoverlapping(d.text[a..].as_ptr(), plain.0, n);
            }
            if !returned.is_null() {
                *returned = if requested == 0 { 0 } else { n as u32 };
            }
            if !run_returned.is_null() {
                *run_returned = if run_count > 0 { 1 } else { 0 };
            }
            if run_count > 0 {
                *run = TS_RUNINFO {
                    uCount: n as u32,
                    r#type: TS_RT_PLAIN,
                };
            }
            if !next.is_null() {
                *next = (a + n) as i32;
            }
        }
        Ok(())
    }
    fn SetText(&self, _: u32, start: i32, end: i32, p: &PCWSTR, n: u32) -> Result<TS_TEXTCHANGE> {
        self.writable()?;
        let (a, b) = self.range(start, end)?;
        if n > 8192
            || self
                .data
                .try_borrow()
                .map_err(|_| Error::from(E_UNEXPECTED))?
                .text
                .len()
                - (b - a)
                + n as usize
                > 8192
        {
            return Err(E_INVALIDARG.into());
        }
        let chars = if n == 0 {
            &[]
        } else {
            unsafe { std::slice::from_raw_parts(p.0, n as usize) }
        };
        let mut d = self
            .data
            .try_borrow_mut()
            .map_err(|_| Error::from(E_UNEXPECTED))?;
        d.text.splice(a..b, chars.iter().copied());
        d.start = start + n as i32;
        d.end = d.start;
        Ok(TS_TEXTCHANGE {
            acpStart: start,
            acpOldEnd: end,
            acpNewEnd: start + n as i32,
        })
    }
    fn GetFormattedText(&self, _: i32, _: i32) -> Result<IDataObject> {
        Err(E_NOTIMPL.into())
    }
    fn GetEmbedded(&self, _: i32, _: *const GUID, _: *const GUID) -> Result<IUnknown> {
        Err(E_NOTIMPL.into())
    }
    fn QueryInsertEmbedded(&self, _: *const GUID, _: *const FORMATETC) -> Result<BOOL> {
        Ok(BOOL(0))
    }
    fn InsertEmbedded(
        &self,
        _: u32,
        _: i32,
        _: i32,
        _: Option<&IDataObject>,
    ) -> Result<TS_TEXTCHANGE> {
        Err(E_NOTIMPL.into())
    }
    fn InsertTextAtSelection(
        &self,
        flags: u32,
        p: &PCWSTR,
        n: u32,
        a: *mut i32,
        b: *mut i32,
        change: *mut TS_TEXTCHANGE,
    ) -> Result<()> {
        let (s, e) = {
            let d = self
                .data
                .try_borrow()
                .map_err(|_| Error::from(E_UNEXPECTED))?;
            (d.start, d.end)
        };
        unsafe {
            if !a.is_null() {
                *a = s;
            }
            if !b.is_null() {
                *b = s + n as i32;
            }
        }
        if flags & TS_IAS_QUERYONLY == 0 {
            let c = self.SetText(0, s, e, p, n)?;
            if !change.is_null() {
                unsafe {
                    *change = c;
                }
            }
        }
        Ok(())
    }
    fn InsertEmbeddedAtSelection(
        &self,
        _: u32,
        _: Option<&IDataObject>,
        _: *mut i32,
        _: *mut i32,
        _: *mut TS_TEXTCHANGE,
    ) -> Result<()> {
        Err(E_NOTIMPL.into())
    }
    fn RequestSupportedAttrs(&self, _: u32, count: u32, filter: *const GUID) -> Result<()> {
        self.attrs(count, filter)
    }
    fn RequestAttrsAtPosition(
        &self,
        _: i32,
        count: u32,
        filter: *const GUID,
        _: u32,
    ) -> Result<()> {
        self.attrs(count, filter)
    }
    fn RequestAttrsTransitioningAtPosition(
        &self,
        _: i32,
        _: u32,
        _: *const GUID,
        _: u32,
    ) -> Result<()> {
        Ok(())
    }
    fn FindNextAttrTransition(
        &self,
        _: i32,
        halt: i32,
        _: u32,
        _: *const GUID,
        _: u32,
        next: *mut i32,
        found: *mut BOOL,
        offset: *mut i32,
    ) -> Result<()> {
        unsafe {
            *next = halt;
            *found = BOOL(0);
            *offset = 0;
        }
        Ok(())
    }
    fn RetrieveRequestedAttrs(
        &self,
        count: u32,
        out: *mut TS_ATTRVAL,
        fetched: *mut u32,
    ) -> Result<()> {
        let requested = std::mem::take(
            &mut *self
                .attrs
                .try_borrow_mut()
                .map_err(|_| Error::from(E_UNEXPECTED))?,
        );
        let n = requested.len().min(count as usize);
        unsafe {
            *fetched = n as u32;
        }
        for (i, guid) in requested.into_iter().take(n).enumerate() {
            let mut value = VARIANT::default();
            unsafe {
                if guid == GUID_PROP_INPUTSCOPE {
                    let scope: ITfInputScope = Scope.into();
                    (*value.Anonymous.Anonymous).vt = VT_UNKNOWN;
                    (*value.Anonymous.Anonymous).Anonymous.punkVal =
                        std::mem::ManuallyDrop::new(Some(scope.cast()?));
                } else {
                    (*value.Anonymous.Anonymous).vt = VT_BOOL;
                    (*value.Anonymous.Anonymous).Anonymous.boolVal = VARIANT_BOOL(0);
                }
                std::ptr::write(
                    out.add(i),
                    TS_ATTRVAL {
                        idAttr: guid,
                        dwOverlapId: i as u32,
                        varValue: value,
                    },
                );
            }
        }
        Ok(())
    }
    fn GetEndACP(&self) -> Result<i32> {
        Ok(self
            .data
            .try_borrow()
            .map_err(|_| Error::from(E_UNEXPECTED))?
            .text
            .len() as i32)
    }
    fn GetActiveView(&self) -> Result<u32> {
        Ok(0)
    }
    fn GetACPFromPoint(&self, _: u32, _: *const POINT, _: u32) -> Result<i32> {
        Err(TS_E_NOLAYOUT.into())
    }
    fn GetTextExt(
        &self,
        _: u32,
        _: i32,
        _: i32,
        rect: *mut RECT,
        clipped: *mut BOOL,
    ) -> Result<()> {
        let mut r = owner_rect(self.hwnd);
        r.right = (r.left + 20).min(r.right);
        r.bottom = (r.top + 24).min(r.bottom);
        unsafe {
            *rect = r;
            *clipped = BOOL(0);
        }
        Ok(())
    }
    fn GetScreenExt(&self, _: u32) -> Result<RECT> {
        Ok(owner_rect(self.hwnd))
    }
    fn GetWnd(&self, _: u32) -> Result<HWND> {
        Ok(self.hwnd)
    }
}
impl ITfContextOwnerCompositionSink_Impl for Store {
    fn OnStartComposition(&self, _: Option<&ITfCompositionView>) -> Result<BOOL> {
        self.data
            .try_borrow_mut()
            .map_err(|_| Error::from(E_UNEXPECTED))?
            .composing = true;
        Ok(BOOL(1))
    }
    fn OnUpdateComposition(
        &self,
        _: Option<&ITfCompositionView>,
        _: Option<&ITfRange>,
    ) -> Result<()> {
        Ok(())
    }
    fn OnEndComposition(&self, _: Option<&ITfCompositionView>) -> Result<()> {
        self.data
            .try_borrow_mut()
            .map_err(|_| Error::from(E_UNEXPECTED))?
            .composing = false;
        Ok(())
    }
}

#[implement(ITfInputScope)]
struct Scope;
impl ITfInputScope_Impl for Scope {
    fn GetInputScopes(&self, out: *mut *mut InputScope, count: *mut u32) -> Result<()> {
        unsafe {
            let p = CoTaskMemAlloc(std::mem::size_of::<InputScope>()) as *mut InputScope;
            if p.is_null() {
                return Err(E_OUTOFMEMORY.into());
            }
            *p = IS_DEFAULT;
            *out = p;
            *count = 1;
        }
        Ok(())
    }
    fn GetPhrase(&self, out: *mut *mut BSTR, count: *mut u32) -> Result<()> {
        unsafe {
            *out = std::ptr::null_mut();
            *count = 0;
        }
        Ok(())
    }
    fn GetRegularExpression(&self) -> Result<BSTR> {
        Err(E_NOTIMPL.into())
    }
    fn GetSRGS(&self) -> Result<BSTR> {
        Err(E_NOTIMPL.into())
    }
    fn GetXML(&self) -> Result<BSTR> {
        Err(E_NOTIMPL.into())
    }
}

#[derive(Default)]
struct Candidates {
    element: Option<ITfCandidateListUIElementBehavior>,
    strings: Vec<String>,
    selection: usize,
    pages: Vec<u32>,
    current_page: usize,
}
#[implement(ITfUIElementSink)]
struct UiSink {
    manager: ITfUIElementMgr,
    candidates: Rc<RefCell<Candidates>>,
}
impl UiSink {
    fn update(&self, id: u32) -> Result<()> {
        unsafe {
            let element = self.manager.GetUIElement(id)?;
            let Ok(list) = element.cast::<ITfCandidateListUIElement>() else {
                return Ok(());
            };
            let count = list.GetCount()?.min(256);
            let mut pages = vec![0; 256];
            let mut page_count = 0;
            let _ = list.GetPageIndex(&mut pages, &mut page_count);
            pages.truncate((page_count as usize).min(256));
            let strings = (0..count)
                .map(|i| list.GetString(i).map(|s| s.to_string()).unwrap_or_default())
                .collect();
            let selection = list.GetSelection().unwrap_or(0) as usize;
            let current_page = list.GetCurrentPage().unwrap_or(0) as usize;
            let element = element.cast().ok();
            let old = std::mem::replace(
                &mut *self
                    .candidates
                    .try_borrow_mut()
                    .map_err(|_| Error::from(E_UNEXPECTED))?,
                Candidates {
                    strings,
                    selection,
                    current_page,
                    pages,
                    element,
                },
            );
            drop(old);
            Ok(())
        }
    }
}
impl ITfUIElementSink_Impl for UiSink {
    fn BeginUIElement(&self, id: u32, show: *mut BOOL) -> Result<()> {
        unsafe {
            *show = BOOL(0);
        }
        self.update(id)
    }
    fn UpdateUIElement(&self, id: u32) -> Result<()> {
        self.update(id)
    }
    fn EndUIElement(&self, _: u32) -> Result<()> {
        let old = std::mem::take(
            &mut *self
                .candidates
                .try_borrow_mut()
                .map_err(|_| Error::from(E_UNEXPECTED))?,
        );
        drop(old);
        Ok(())
    }
}

enum Command {
    Key(u32, bool),
    Literal(String),
    Enable(bool),
    Candidate(usize),
}
struct SavedCompartment {
    manager: ITfCompartmentMgr,
    guid: GUID,
    value: VARIANT,
}
impl SavedCompartment {
    unsafe fn capture(manager: &ITfCompartmentMgr, guid: GUID) -> Result<Self> {
        Ok(Self {
            manager: manager.clone(),
            guid,
            value: manager.GetCompartment(&guid)?.GetValue()?,
        })
    }
    unsafe fn restore(&self, client: u32) {
        if self.value.Anonymous.Anonymous.vt == VT_EMPTY {
            let _ = self.manager.ClearCompartment(client, &self.guid);
        } else if let Ok(c) = self.manager.GetCompartment(&self.guid) {
            let _ = c.SetValue(client, &self.value);
        }
    }
}
impl Drop for SavedCompartment {
    fn drop(&mut self) {
        unsafe {
            let _ = VariantClear(&mut self.value);
        }
    }
}
struct Host {
    manager: ITfThreadMgrEx,
    document: ITfDocumentMgr,
    context: ITfContext,
    keys: ITfKeystrokeMgr,
    source: ITfSource,
    cookie: u32,
    _store: ITextStoreACP,
    _ui: ITfUIElementSink,
    store_sink: Rc<RefCell<Option<ITextStoreACPSink>>>,
    data: Rc<RefCell<Data>>,
    candidates: Rc<RefCell<Candidates>>,
    hwnd: HWND,
    client: u32,
    enabled: bool,
    previous_focus: Option<ITfDocumentMgr>,
    previous_association: Option<ITfDocumentMgr>,
    saved: Vec<SavedCompartment>,
    tag: usize,
    queue: VecDeque<Command>,
    inflight: Option<(u32, bool, Instant)>,
    finishing: Option<Instant>,
    status: &'static str,
    was_focused: bool,
    closing: bool,
    reopen: bool,
    next_queue: VecDeque<Command>,
    cancel_inflight: bool,
}
impl Host {
    unsafe fn new(hwnd: HWND) -> Result<Self> {
        let manager: ITfThreadMgrEx =
            CoCreateInstance(&CLSID_TF_ThreadMgr, None, CLSCTX_INPROC_SERVER)?;
        let mut guard = InitGuard {
            manager: manager.clone(),
            activated: false,
            source: None,
            cookie: None,
        };
        let mut client = 0;
        manager.ActivateEx(&mut client, TF_TMAE_UIELEMENTENABLEDONLY)?;
        guard.activated = true;
        let previous_focus = manager.GetFocus().ok();
        let data = Rc::new(RefCell::new(Data::default()));
        let store_sink = Rc::new(RefCell::new(None));
        let store: ITextStoreACP = Store {
            attrs: RefCell::new(Vec::new()),
            data: data.clone(),
            sink: store_sink.clone(),
            lock: Cell::new(0),
            queued_lock: Cell::new(0),
            hwnd,
        }
        .into();
        let document = manager.CreateDocumentMgr()?;
        let mut context = None;
        let mut edit_cookie = 0;
        document.CreateContext(client, 0, &store, &mut context, &mut edit_cookie)?;
        let context = context.ok_or_else(|| Error::from(E_FAIL))?;
        document.Push(&context)?;
        let candidates = Rc::new(RefCell::new(Candidates::default()));
        let ui: ITfUIElementSink = UiSink {
            manager: manager.cast()?,
            candidates: candidates.clone(),
        }
        .into();
        let source: ITfSource = manager.cast()?;
        let cookie = source.AdviseSink(&ITfUIElementSink::IID, &ui)?;
        guard.source = Some(source.clone());
        guard.cookie = Some(cookie);
        let keys = manager.cast()?;
        let compartments: ITfCompartmentMgr = manager.cast()?;
        let saved = vec![
            SavedCompartment::capture(&compartments, GUID_COMPARTMENT_KEYBOARD_OPENCLOSE)?,
            SavedCompartment::capture(
                &compartments,
                GUID_COMPARTMENT_KEYBOARD_INPUTMODE_CONVERSION,
            )?,
        ];
        if !SetWindowSubclass(hwnd, Some(window_input), SUBCLASS_ID, 0).as_bool() {
            return Err(E_FAIL.into());
        }
        let previous_association = associate(&manager, hwnd, Some(&document))?;
        let tag = NEXT_TAG.with(|n| {
            let next = (n.get() + 1) & 0xFFFF;
            n.set(next);
            TAG_PREFIX | next
        });
        // From here on Host::drop owns rollback, including AssociateFocus if SetFocus fails.
        guard.activated = false;
        guard.cookie = None;
        let host = Self {
            manager,
            document,
            context,
            keys,
            source,
            cookie,
            _store: store,
            _ui: ui,
            store_sink,
            data,
            candidates,
            hwnd,
            client,
            enabled: false,
            previous_focus,
            previous_association,
            saved,
            tag,
            queue: VecDeque::new(),
            inflight: None,
            finishing: None,
            status: "Windows TSF",
            was_focused: false,
            closing: false,
            reopen: false,
            next_queue: VecDeque::new(),
            cancel_inflight: false,
        };
        host.manager.SetFocus(&host.document)?;
        install_filter()?;
        Ok(host)
    }
    fn focused(&self) -> bool {
        unsafe { GetForegroundWindow() == self.hwnd && GetFocus() == self.hwnd }
    }
    fn cancel_for_focus(&mut self) {
        for command in &self.queue {
            if let Command::Enable(enabled) = command {
                self.enabled = *enabled;
            }
        }
        self.queue.clear();
        if self.inflight.is_some() {
            self.cancel_inflight = true;
        }
        self.finishing = None;
        if self.was_focused {
            self.cancel_composition();
            if let Ok(mut d) = self.data.try_borrow_mut() {
                d.events.clear();
            }
        }
        self.was_focused = false;
        self.status = "focus_required";
    }
    fn cancel_composition(&self) {
        unsafe {
            if let Ok(owner) = self.context.cast::<ITfContextOwnerCompositionServices>() {
                let _ = owner.TerminateComposition(None);
            }
        }
        self.clear_document(false);
        if let Ok(mut d) = self.data.try_borrow_mut() {
            d.composing = false;
        }
        let old = self
            .candidates
            .try_borrow_mut()
            .ok()
            .map(|mut c| std::mem::take(&mut *c));
        drop(old);
    }
    fn drive(&mut self) {
        if !self.focused() {
            self.cancel_for_focus();
            return;
        }
        if !self.was_focused {
            self.status = "Windows TSF";
            unsafe {
                let _ = self.set_open(self.enabled);
            }
        }
        self.was_focused = true;
        self.flush_commit();
        if let Some((_, _, start)) = self.inflight {
            if start.elapsed() > Duration::from_secs(2) {
                self.queue.clear();
                self.cancel_inflight = true;
                self.cancel_composition();
                self.status = "input_unavailable";
            }
            return;
        }
        if let Some(start) = self.finishing {
            if self.composing() {
                if start.elapsed() > Duration::from_secs(2) {
                    self.queue.clear();
                    self.finishing = None;
                    self.cancel_composition();
                    self.status = "input_unavailable";
                }
                return;
            }
            self.finishing = None;
            self.flush_commit();
        }
        // Local commands can finish synchronously; only one OS key pair is outstanding.
        for _ in 0..128 {
            let Some(command) = self.queue.pop_front() else {
                break;
            };
            match command {
                Command::Enable(enabled) => {
                    if !enabled {
                        self.cancel_composition();
                    }
                    self.enabled = enabled;
                    unsafe {
                        if self.set_open(enabled).is_err() {
                            self.status = "input_unavailable";
                        }
                    }
                }
                Command::Key(vk, shift) => {
                    if self.enabled
                        && shift
                        && matches!(vk,32|48..=57|65..=90|0xBA..=0xC0|0xDB..=0xDE)
                    {
                        let mut state = [0u8; 256];
                        state[VK_SHIFT.0 as usize] = 0x80;
                        let mut chars = [0u16; 8];
                        let n = unsafe {
                            ToUnicode(
                                vk,
                                MapVirtualKeyW(vk, MAPVK_VK_TO_VSC),
                                Some(&state),
                                &mut chars,
                                0,
                            )
                        };
                        if n > 0 {
                            self.queue
                                .push_front(Command::Literal(String::from_utf16_lossy(
                                    &chars[..(n as usize).min(chars.len())],
                                )));
                        }
                        continue;
                    }
                    if self.enabled {
                        if !self.send_key(vk, shift) {
                            self.queue.clear();
                        }
                        break;
                    } else {
                        unsafe {
                            self.process_key(
                                vk,
                                shift,
                                LPARAM((1 | (MapVirtualKeyW(vk, MAPVK_VK_TO_VSC) << 16)) as isize),
                            );
                        }
                    }
                }
                Command::Literal(text) => {
                    if self.composing() {
                        self.queue.push_front(Command::Literal(text));
                        self.finishing = Some(Instant::now());
                        let element = self
                            .candidates
                            .try_borrow()
                            .ok()
                            .and_then(|c| c.element.clone());
                        let finalized = element
                            .map(|e| unsafe { e.Finalize().is_ok() })
                            .unwrap_or(false);
                        if !finalized {
                            self.send_key(13, false);
                        }
                        break;
                    }
                    self.flush_commit();
                    if let Ok(mut d) = self.data.try_borrow_mut() {
                        d.events.push(Event { text, key: 0 });
                    }
                }
                Command::Candidate(index) => {
                    let element = self.candidates.try_borrow().ok().and_then(|c| {
                        if index < c.strings.len() {
                            c.element.clone()
                        } else {
                            None
                        }
                    });
                    if let Some(e) = element {
                        unsafe {
                            if e.SetSelection(index as u32).is_ok() {
                                let _ = e.Finalize();
                            }
                        }
                        self.finishing = Some(Instant::now());
                        break;
                    }
                }
            }
        }
    }
    fn composing(&self) -> bool {
        self.data.try_borrow().map(|d| d.composing).unwrap_or(true)
    }
    fn send_key(&mut self, vk: u32, shift: bool) -> bool {
        unsafe {
            if !self.focused() {
                self.cancel_for_focus();
                return false;
            }
            // Never combine a UI key with a physical system shortcut modifier.
            if [VK_CONTROL, VK_MENU, VK_LWIN, VK_RWIN]
                .iter()
                .any(|v| GetAsyncKeyState(v.0 as i32) < 0)
            {
                self.status = "modifier_held";
                return false;
            }
            let input = |flags| INPUT {
                r#type: INPUT_KEYBOARD,
                Anonymous: INPUT_0 {
                    ki: KEYBDINPUT {
                        wVk: VIRTUAL_KEY(vk as u16),
                        dwFlags: flags,
                        dwExtraInfo: self.tag,
                        ..Default::default()
                    },
                },
            };
            self.inflight = Some((vk, shift, Instant::now()));
            let sent = SendInput(
                &[input(KEYBD_EVENT_FLAGS(0)), input(KEYEVENTF_KEYUP)],
                std::mem::size_of::<INPUT>() as i32,
            );
            if sent != 2 {
                if sent == 1 {
                    let _ = SendInput(
                        &[input(KEYEVENTF_KEYUP)],
                        std::mem::size_of::<INPUT>() as i32,
                    );
                }
                self.inflight = None;
                self.status = "input_unavailable";
                return false;
            }
            true
        }
    }
    unsafe fn set_open(&self, open: bool) -> Result<()> {
        let manager: ITfCompartmentMgr = self.manager.cast()?;
        let mut value = VARIANT::default();
        (*value.Anonymous.Anonymous).vt = VT_I4;
        (*value.Anonymous.Anonymous).Anonymous.lVal = if open { 1 } else { 0 };
        manager
            .GetCompartment(&GUID_COMPARTMENT_KEYBOARD_OPENCLOSE)?
            .SetValue(self.client, &value)?;
        manager
            .GetCompartment(&GUID_COMPARTMENT_KEYBOARD_INPUTMODE_CONVERSION)?
            .SetValue(self.client, &value)?;
        let context: ITfCompartmentMgr = self.context.cast()?;
        (*value.Anonymous.Anonymous).Anonymous.lVal = 0;
        context
            .GetCompartment(&GUID_COMPARTMENT_KEYBOARD_DISABLED)?
            .SetValue(self.client, &value)?;
        context
            .GetCompartment(&GUID_COMPARTMENT_EMPTYCONTEXT)?
            .SetValue(self.client, &value)?;
        Ok(())
    }
    unsafe fn process_key(&self, vk: u32, shift: bool, param: LPARAM) {
        let mut previous = [0u8; 256];
        let _ = GetKeyboardState(&mut previous);
        let mut current = [0u8; 256];
        if shift {
            current[VK_SHIFT.0 as usize] = 0x80;
        }
        current[vk as usize] = 0x80;
        let _ = SetKeyboardState(&current);
        let eaten = self.enabled
            && self
                .keys
                .TestKeyDown(WPARAM(vk as usize), param)
                .map(|v| v.as_bool())
                .unwrap_or(false)
            && self
                .keys
                .KeyDown(WPARAM(vk as usize), param)
                .map(|v| v.as_bool())
                .unwrap_or(false);
        if !eaten {
            let event = if matches!(vk, 8 | 9 | 13 | 27 | 33..=40 | 46) {
                Some(Event {
                    text: String::new(),
                    key: vk,
                })
            } else {
                let mut chars = [0u16; 8];
                let n = ToUnicode(
                    vk,
                    MapVirtualKeyW(vk, MAPVK_VK_TO_VSC),
                    Some(&current),
                    &mut chars,
                    0,
                );
                if n > 0 {
                    Some(Event {
                        text: String::from_utf16_lossy(&chars[..(n as usize).min(chars.len())]),
                        key: 0,
                    })
                } else {
                    None
                }
            };
            if let Some(event) = event {
                if let Ok(mut d) = self.data.try_borrow_mut() {
                    d.events.push(event);
                }
            }
        }
        let _ = SetKeyboardState(&previous);
    }
    fn snapshot(&mut self) -> Snapshot {
        if self.cancel_inflight {
            return Snapshot {
                available: true,
                ime_enabled: self.enabled,
                status: self.status.into(),
                ..Default::default()
            };
        }
        self.flush_commit();
        let Ok(mut d) = self.data.try_borrow_mut() else {
            return Snapshot::default();
        };
        let Ok(c) = self.candidates.try_borrow() else {
            return Snapshot::default();
        };
        let start =
            (c.pages.get(c.current_page).copied().unwrap_or(0) as usize).min(c.strings.len());
        let size = c
            .pages
            .get(c.current_page + 1)
            .map(|n| (*n as usize).saturating_sub(start))
            .unwrap_or(c.strings.len().saturating_sub(start));
        Snapshot {
            available: true,
            ime_enabled: self.enabled,
            composition: String::from_utf16_lossy(&d.text),
            cursor: d.end.max(0) as usize,
            candidates: c.strings.clone(),
            selection: c.selection,
            page_start: start,
            page_size: size,
            events: std::mem::take(&mut d.events),
            status: self.status.into(),
        }
    }
    fn flush_commit(&self) {
        if !self.composing() {
            self.clear_document(true);
        }
    }
    fn clear_document(&self, commit: bool) {
        let old_len = {
            let Ok(mut d) = self.data.try_borrow_mut() else {
                return;
            };
            if d.text.is_empty() {
                return;
            }
            let old = d.text.len();
            if commit {
                let text = String::from_utf16_lossy(&d.text);
                d.events.push(Event { text, key: 0 });
            }
            d.text.clear();
            d.start = 0;
            d.end = 0;
            old
        };
        let sink = self.store_sink.try_borrow().ok().and_then(|s| s.clone());
        if let Some(sink) = sink {
            unsafe {
                let _ = sink.OnTextChange(
                    TEXT_STORE_TEXT_CHANGE_FLAGS(0),
                    &TS_TEXTCHANGE {
                        acpStart: 0,
                        acpOldEnd: old_len as i32,
                        acpNewEnd: 0,
                    },
                );
                let _ = sink.OnSelectionChange();
            }
        }
    }
}
fn owner_rect(hwnd: HWND) -> RECT {
    unsafe {
        let mut r = RECT::default();
        let _ = GetClientRect(hwnd, &mut r);
        let mut point = POINT::default();
        ClientToScreen(hwnd, &mut point);
        RECT {
            left: point.x,
            top: point.y,
            right: point.x + r.right.max(1),
            bottom: point.y + r.bottom.max(1),
        }
    }
}
unsafe fn associate(
    manager: &ITfThreadMgrEx,
    hwnd: HWND,
    document: Option<&ITfDocumentMgr>,
) -> Result<Option<ITfDocumentMgr>> {
    let mut previous = std::ptr::null_mut();
    (Interface::vtable(manager).base__.AssociateFocus)(
        Interface::as_raw(manager),
        hwnd,
        document
            .map(Interface::as_raw)
            .unwrap_or(std::ptr::null_mut()),
        &mut previous,
    )
    .ok()?;
    Ok(if previous.is_null() {
        None
    } else {
        Some(ITfDocumentMgr::from_raw(previous))
    })
}
struct InitGuard {
    manager: ITfThreadMgrEx,
    activated: bool,
    source: Option<ITfSource>,
    cookie: Option<u32>,
}
impl Drop for InitGuard {
    fn drop(&mut self) {
        unsafe {
            if let (Some(source), Some(cookie)) = (&self.source, self.cookie) {
                let _ = source.UnadviseSink(cookie);
            }
            if self.activated {
                let _ = self.manager.Deactivate();
            }
        }
    }
}
impl Drop for Host {
    fn drop(&mut self) {
        unsafe {
            self.queue.clear();
            self.cancel_composition();
            let _ = self.source.UnadviseSink(self.cookie);
            let _ = associate(&self.manager, self.hwnd, self.previous_association.as_ref());
            if self
                .manager
                .GetFocus()
                .map(|d| d == self.document)
                .unwrap_or(false)
            {
                let _ = self.manager.SetFocus(self.previous_focus.as_ref());
            }
            let _ = self.document.Pop(TF_POPF_ALL);
            for saved in &self.saved {
                saved.restore(self.client);
            }
            let _ = self.manager.Deactivate();
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    fn store() -> Store {
        Store {
            attrs: RefCell::new(Vec::new()),
            data: Rc::new(RefCell::new(Data {
                text: "abc".encode_utf16().collect(),
                start: 0,
                end: 0,
                composing: false,
                events: Vec::new(),
            })),
            sink: Rc::new(RefCell::new(None)),
            lock: Cell::new(0),
            queued_lock: Cell::new(0),
            hwnd: HWND(0),
        }
    }
    #[test]
    fn acp_length_and_run_query_advances_without_a_plain_buffer() {
        let s = store();
        let mut plain_count = 99;
        let mut run = TS_RUNINFO::default();
        let mut run_count = 0;
        let mut next = 0;
        s.GetText(
            0,
            -1,
            PWSTR::null(),
            0,
            &mut plain_count,
            &mut run,
            1,
            &mut run_count,
            &mut next,
        )
        .unwrap();
        assert_eq!((plain_count, run_count, run.uCount, next), (0, 1, 3, 3));
    }
    #[test]
    fn acp_mutations_require_write_lock_and_valid_ranges() {
        let s = store();
        let text = [b'x' as u16];
        assert!(s.SetText(0, 0, 1, &PCWSTR(text.as_ptr()), 1).is_err());
        s.lock.set(TS_LF_READWRITE.0);
        let change = s.SetText(0, 0, 1, &PCWSTR(text.as_ptr()), 1).unwrap();
        assert_eq!(change.acpNewEnd, 1);
        assert_eq!(String::from_utf16_lossy(&s.data.borrow().text), "xbc");
        assert!(s.SetText(0, 4, 5, &PCWSTR(text.as_ptr()), 1).is_err());
    }
}
