//! Private virtual-keyboard text session. JNI returns composition, candidates and ordered commits.
//! Windows keys use a focus-checked, session-tagged input path; other platforms report unavailable.

#[cfg(windows)]
mod windows;

#[derive(Clone, Debug, Default)]
pub struct Event {
    pub text: String,
    pub key: u32,
}

#[derive(Clone, Debug, Default)]
pub struct Snapshot {
    pub available: bool,
    pub ime_enabled: bool,
    pub composition: String,
    pub cursor: usize,
    pub candidates: Vec<String>,
    pub selection: usize,
    pub page_start: usize,
    pub page_size: usize,
    pub events: Vec<Event>,
    pub status: String,
}

impl Snapshot {
    pub fn json(&self) -> String {
        serde_json::json!({
            "available": self.available, "imeEnabled": self.ime_enabled,
            "composition": self.composition, "cursor": self.cursor,
            "candidates": self.candidates, "selection": self.selection,
            "pageStart": self.page_start, "pageSize": self.page_size,
            "events": self.events.iter().map(|e| serde_json::json!({"text":e.text,"key":e.key})).collect::<Vec<_>>(),
            "status": self.status,
        }).to_string()
    }
}

pub fn open(owner: i64) -> bool {
    #[cfg(windows)]
    {
        windows::open(owner)
    }
    #[cfg(not(windows))]
    {
        let _ = owner;
        false
    }
}

pub fn close() {
    #[cfg(windows)]
    windows::close();
}

/// kind: 0 key stroke (value=Windows VK, shift modifier), 1 IME mode, 2 candidate, 3 candidate page.
pub fn command(kind: i32, value: i32, shift: bool) -> bool {
    #[cfg(windows)]
    {
        windows::command(kind, value, shift)
    }
    #[cfg(not(windows))]
    {
        let _ = (kind, value, shift);
        false
    }
}

pub fn poll() -> Snapshot {
    #[cfg(windows)]
    {
        windows::poll()
    }
    #[cfg(not(windows))]
    {
        Snapshot::default()
    }
}

pub fn text(text: String) -> bool {
    #[cfg(windows)]
    {
        windows::text(text)
    }
    #[cfg(not(windows))]
    {
        let _ = text;
        false
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn json_keeps_unicode_and_event_order() {
        let s = Snapshot {
            composition: "你好".into(),
            events: vec![
                Event {
                    text: "你".into(),
                    key: 0,
                },
                Event {
                    text: String::new(),
                    key: 13,
                },
            ],
            ..Snapshot::default()
        };
        let json: serde_json::Value = serde_json::from_str(&s.json()).unwrap();
        assert_eq!(json["composition"], "你好");
        assert_eq!(json["events"][0]["text"], "你");
        assert_eq!(json["events"][1]["key"], 13);
    }
}
