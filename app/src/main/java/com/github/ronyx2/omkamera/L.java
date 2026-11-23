package com.github.ronyx2.omkamera;

import android.content.Context;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Language of the camera display. The app is written in German; for English every text is
 * translated where it is drawn ({@link #t}), so the German code paths stay unchanged.
 *
 * Translation: first the whole text (assets/lang/en.tsv), otherwise known German pieces inside
 * the text are replaced (for texts that are put together, such as "nach 10 min"). Texts that
 * stay German are collected and can be listed on the debug page (/api/i18n).
 */
public final class L {

    public static final String[] CHOICES = {"wie Kamera", "Deutsch", "English"};

    private static boolean english;
    private static final Map<String, String> FULL = new HashMap<String, String>();
    /** German pieces, longest first */
    private static final List<String[]> PIECES = new ArrayList<String[]>();
    /** Patterns for texts with numbers in the middle ("re:" lines in the file) */
    private static final List<Object[]> PATTERNS = new ArrayList<Object[]>();
    private static final Map<String, String> CACHE = new HashMap<String, String>();
    private static final Set<String> MISSING = new LinkedHashSet<String>();
    private static boolean loaded;

    private L() {
    }

    /** 0 = as camera (Android language), 1 = German, 2 = English */
    public static synchronized void init(Context ctx, int choice) {
        boolean en;
        if (choice == 1) {
            en = false;
        } else if (choice == 2) {
            en = true;
        } else {
            en = !"de".equals(Locale.getDefault().getLanguage());
        }
        if (en && !loaded) {
            load(ctx);
        }
        english = en;
        CACHE.clear();
    }

    public static boolean english() {
        return english;
    }

    private static void load(Context ctx) {
        try {
            BufferedReader r = new BufferedReader(new InputStreamReader(ctx.getAssets().open("lang/en.tsv"), "UTF-8"));
            String line;
            while ((line = r.readLine()) != null) {
                int tab = line.indexOf('\t');
                if (tab <= 0 || line.startsWith("#")) {
                    continue;
                }
                String de = unescape(line.substring(0, tab));
                String en = unescape(line.substring(tab + 1));
                if (de.startsWith("re:")) {
                    PATTERNS.add(new Object[] {java.util.regex.Pattern.compile(de.substring(3)), en});
                    continue;
                }
                FULL.put(de, en);
                int n = letters(de);
                if (n >= 3 || (n == 2 && n < de.length())) {
                    PIECES.add(new String[] {de, en});
                }
            }
            r.close();
            Collections.sort(PIECES, new Comparator<String[]>() {
                @Override
                public int compare(String[] a, String[] b) {
                    return b[0].length() - a[0].length();
                }
            });
            loaded = true;
        } catch (Exception e) {
            Log.error("lang", e);
        }
    }

    /** In the file, leading and trailing spaces are written as \s */
    private static String unescape(String s) {
        return s.replace("\\s", " ").replace("\\t", "\t").replace("\\n", "\n");
    }

    private static int letters(String s) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            if (Character.isLetter(s.charAt(i))) {
                n++;
            }
        }
        return n;
    }

    /** The text in the display language */
    public static String t(String s) {
        if (!english || s == null || s.length() == 0) {
            return s;
        }
        synchronized (L.class) {
            String c = CACHE.get(s);
            if (c != null) {
                return c;
            }
            String r = translate(s);
            if (CACHE.size() > 3000) {
                CACHE.clear();
            }
            CACHE.put(s, r);
            boolean untouched = r.equals(s) && !FULL.containsKey(s) && hasLowerWord(s);
            if ((untouched || looksGerman(r)) && MISSING.size() < 2000) {
                MISSING.add(s);
            }
            return r;
        }
    }

    private static String translate(String s) {
        String f = FULL.get(s);
        if (f != null) {
            return f;
        }
        for (Object[] p : PATTERNS) {
            java.util.regex.Matcher m = ((java.util.regex.Pattern) p[0]).matcher(s);
            if (m.matches()) {
                return m.replaceFirst((String) p[1]);
            }
        }
        // Replace known pieces; translated parts are protected by markers, so they are not
        // translated a second time by a shorter piece.
        StringBuilder out = new StringBuilder(s);
        List<String> parts = new ArrayList<String>();
        for (String[] p : PIECES) {
            String de = p[0];
            int from = 0;
            int at;
            while ((at = out.indexOf(de, from)) >= 0) {
                int end = at + de.length();
                if (!boundary(out, at, end, de)) {
                    from = at + 1;
                    continue;
                }
                String mark = "\u0001" + parts.size() + "\u0002";
                parts.add(p[1]);
                out.replace(at, end, mark);
                from = at + mark.length();
            }
        }
        if (parts.isEmpty()) {
            return s;
        }
        String r = out.toString();
        for (int i = 0; i < parts.size(); i++) {
            r = r.replace("\u0001" + i + "\u0002", parts.get(i));
        }
        return r;
    }

    /** A piece that starts or ends with a letter must not be part of a longer word */
    private static boolean boundary(CharSequence s, int at, int end, String piece) {
        if (Character.isLetter(piece.charAt(0)) && at > 0 && isWordChar(s.charAt(at - 1))) {
            return false;
        }
        if (Character.isLetter(piece.charAt(piece.length() - 1)) && end < s.length() && isWordChar(s.charAt(end))) {
            return false;
        }
        return true;
    }

    private static boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c);
    }

    /** At least one word of 4 letters with lower case (not just codes like RAW, AWB, ILCE-6000) */
    private static boolean hasLowerWord(String s) {
        int run = 0;
        boolean lower = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isLetter(c)) {
                run++;
                lower |= Character.isLowerCase(c);
                if (run >= 4 && lower) {
                    return true;
                }
            } else {
                run = 0;
                lower = false;
            }
        }
        return false;
    }

    /** Rough check for German text that was left over */
    private static boolean looksGerman(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ("äöüÄÖÜß".indexOf(c) >= 0) {
                return true;
            }
        }
        String l = " " + s.toLowerCase(Locale.ROOT) + " ";
        for (String w : GERMAN_WORDS) {
            if (l.contains(w)) {
                return true;
            }
        }
        return false;
    }

    private static final String[] GERMAN_WORDS = {
        " der ", " die ", " das ", " und ", " nicht ", " mit ", " ein ", " aus ", " bei ", " nach ", " vor ",
        " zeit", " bild", " einstellung", " wird ", " ist ", " für ", " auf ", " oder ", " uhr",
    };

    /** Translates a text line by line (status pages) */
    public static String lines(String s) {
        if (!english || s == null) {
            return s;
        }
        String[] ls = s.split("\n", -1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < ls.length; i++) {
            if (i > 0) {
                sb.append('\n');
            }
            sb.append(t(ls[i]));
        }
        return sb.toString();
    }

    /** Texts that stayed German, for completing the translation */
    public static synchronized String missing() {
        StringBuilder sb = new StringBuilder();
        sb.append(english ? "Sprache: English\n" : "Sprache: Deutsch (keine Sammlung)\n");
        sb.append(MISSING.size()).append(" Texte ohne Übersetzung\n\n");
        for (String s : MISSING) {
            sb.append(s.replace("\n", "\\n")).append(" \t→ ").append(CACHE.containsKey(s) ? CACHE.get(s).replace("\n", "\\n") : "").append('\n');
        }
        return sb.toString();
    }
}
