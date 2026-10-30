package com.example.andrologpassword;

import android.app.Activity;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Tiny training app; the password and secret have no real-world use. */
public final class MainActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(60, 110, 60, 40);
        TextView title = new TextView(this);
        title.setText("Démo AndroLog"); title.setTextSize(29);
        TextView prompt = new TextView(this);
        prompt.setText("Entrez le mot de passe pour accéder au secret."); prompt.setTextSize(17);
        EditText password = new EditText(this);
        password.setHint("Mot de passe"); password.setSingleLine(true);
        password.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        Button check = new Button(this); check.setText("Vérifier");
        TextView result = new TextView(this); result.setTextSize(19); result.setGravity(Gravity.CENTER);
        check.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                String value = password.getText().toString();
                boolean allowed = new PasswordCheck().check(value);
                result.setText(allowed ? "Accès autorisé : secret de démonstration" : "Accès refusé");
            }
        });
        box.addView(title); box.addView(prompt); box.addView(password); box.addView(check); box.addView(result);
        setContentView(box);
        // Optional synthetic input for reproducible automated captures, same button handler as manual use.
        String testInput = getIntent().getStringExtra("password");
        if (testInput != null) { password.setText(testInput); check.performClick(); }
    }
}
