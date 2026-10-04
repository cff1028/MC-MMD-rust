package com.shiroha.mmdskin.ui.spatial.lumen.model;

/** A card backed by the current instance's native save summary or saved server entry. */
public record WorldEntry(String id, String name, String subtitle, String kind, int palette, boolean favorite) {}
