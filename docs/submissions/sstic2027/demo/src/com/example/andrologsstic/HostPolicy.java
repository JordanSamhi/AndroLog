package com.example.andrologsstic;

import android.net.Uri;

public final class HostPolicy implements UrlPolicy {
    @Override public boolean allow(String url) {
        Uri parsed = Uri.parse(url);
        return "https".equals(parsed.getScheme()) && "trusted.example".equals(parsed.getHost());
    }
}
