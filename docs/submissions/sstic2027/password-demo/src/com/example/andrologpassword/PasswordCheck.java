package com.example.andrologpassword;

/** Deliberately reversible obfuscation for a teaching demonstration. */
public final class PasswordCheck {
    public boolean check(String password) {
        String expected = decodePassword();
        if (password.equals(expected)) {
            return showSecret();
        }
        return showError();
    }

    private String decodePassword() {
        byte[] encoded = {90, 90, 93, 64, 74, 27, 25, 27, 30};
        char[] decoded = new char[encoded.length];
        for (int i = 0; i < encoded.length; i++) {
            decoded[i] = (char) (encoded[i] ^ 0x29);
        }
        return new String(decoded);
    }

    public boolean showSecret() {
        return true;
    }

    public boolean showError() {
        return false;
    }
}
