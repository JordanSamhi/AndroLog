package com.example.andrologsstic;

import android.net.Uri;

/** Deliberately small example for following an external value through code. */
public final class UrlAudit {
    public String inspect(String raw, boolean lockdown) {
        String normalized = normalize(raw);
        UrlPolicy policy = choosePolicy(lockdown);
        boolean allowed = policy.allow(normalized);
        if (allowed) return accepted(normalized);
        return rejected(normalized);
    }

    public String normalize(String raw) {
        if (raw == null) return "";
        return Uri.parse(raw.trim()).normalizeScheme().toString();
    }

    public UrlPolicy choosePolicy(boolean lockdown) {
        if (lockdown) return new DenyPolicy();
        return new HostPolicy();
    }

    public String accepted(String url) {
        return "ACCEPTÉE\n" + url;
    }

    public String rejected(String url) {
        return "REFUSÉE\n" + url;
    }
}
