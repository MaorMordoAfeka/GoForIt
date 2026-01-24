package javax.lang.model;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Android runtime shim for javax.lang.model.SourceVersion.
 *
 * GraphHopper 7.x references SourceVersion for identifier/name validation,
 * but Android does not ship the Java compiler module (java.compiler).
 *
 * This implementation provides only the minimal behavior GraphHopper needs.
 */
public enum SourceVersion {
    RELEASE_0, RELEASE_1, RELEASE_2, RELEASE_3, RELEASE_4, RELEASE_5,
    RELEASE_6, RELEASE_7, RELEASE_8, RELEASE_9, RELEASE_10, RELEASE_11,
    RELEASE_12, RELEASE_13, RELEASE_14, RELEASE_15, RELEASE_16, RELEASE_17;

    private static final Set<String> KEYWORDS = new HashSet<>(Arrays.asList(
            "abstract","assert","boolean","break","byte","case","catch","char","class",
            "const","continue","default","do","double","else","enum","extends","final",
            "finally","float","for","goto","if","implements","import","instanceof","int",
            "interface","long","native","new","package","private","protected","public",
            "return","short","static","strictfp","super","switch","synchronized","this",
            "throw","throws","transient","try","void","volatile","while",
            // literals
            "true","false","null",
            // reserved/restricted (safe to include for name checks)
            "_","exports","module","open","opens","permits","provides","record",
            "requires","sealed","to","transitive","uses","var","with","yield"
    ));

    public static boolean isKeyword(CharSequence s) {
        return s != null && KEYWORDS.contains(s.toString());
    }

    public static boolean isIdentifier(CharSequence s) {
        if (s == null) return false;
        String str = s.toString();
        if (str.isEmpty()) return false;
        if (isKeyword(str)) return false;

        int cp = str.codePointAt(0);
        if (!Character.isJavaIdentifierStart(cp)) return false;

        for (int i = Character.charCount(cp); i < str.length();) {
            cp = str.codePointAt(i);
            if (!Character.isJavaIdentifierPart(cp)) return false;
            i += Character.charCount(cp);
        }
        return true;
    }

    public static boolean isName(CharSequence s) {
        if (s == null) return false;
        String str = s.toString();
        if (str.isEmpty()) return false;

        String[] parts = str.split("\\.");
        for (String p : parts) {
            if (!isIdentifier(p)) return false;
        }
        return true;
    }

    public static SourceVersion latest() {
        return RELEASE_17;
    }

    public static SourceVersion latestSupported() {
        return RELEASE_17;
    }
}
