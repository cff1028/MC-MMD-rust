package com.shiroha.mmdskin.ui.spatial.lumen.model;

/** A renderer-neutral avatar card; palette selects the procedural preview artwork. */
public record AvatarEntry(String id, String name, String subtitle, String kind, int palette, boolean favorite) {}
