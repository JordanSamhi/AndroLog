package com.example.andrologpassword;

/** Intentionally insecure, synthetic password for a teaching demonstration. */
public final class PasswordCheck {
    public boolean check(String password) {
        if (password.equals("sstic2027")) {
            return showSecret();
        }
        return showError();
    }

    public boolean showSecret() {
        return true;
    }

    public boolean showError() {
        return false;
    }
}
