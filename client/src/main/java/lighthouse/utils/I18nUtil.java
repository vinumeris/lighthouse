package lighthouse.utils;

import java.util.ResourceBundle;
import java.util.MissingResourceException;
import gnu.gettext.*;

public class I18nUtil {
    private static Boolean translationAvailable;
    private static GettextResource resource;
    private static ResourceBundle locale = loadCurrentLocale();
    
    private static ResourceBundle loadCurrentLocale() {
        try {
            ResourceBundle ret = ResourceBundle.getBundle("lighthouse.locale.lighthouse");
            translationAvailable = true;
            return ret;
        } catch (MissingResourceException e) {
            translationAvailable = false; // If user's locale is not supported yet, we will use the original English strings
        }
        return null;
    }
    
    public static String _(String s) {
        if (translationAvailable) return resource.gettext(locale, s);
        else return s;
    }
}
