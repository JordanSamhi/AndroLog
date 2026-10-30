package com.example.andrologsstic;

public final class DenyPolicy implements UrlPolicy {
    @Override public boolean allow(String url) {
        return false;
    }
}
