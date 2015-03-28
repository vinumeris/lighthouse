package lighthouse.utils;

import org.slf4j.*;

import javax.annotation.*;
import java.util.*;

import static gnu.gettext.GettextResource.*;

public class I18nUtil {
    private static final Logger log = LoggerFactory.getLogger(I18nUtil.class);
    @Nullable public static ResourceBundle locale;

    static {
        try {
            locale = ResourceBundle.getBundle("lighthouse.locale.lighthouse");
            log.info("Using language translations for {}", locale.getLocale());
        } catch (MissingResourceException e) {
            // Ignore.
        }
    }

    public static String tr(String s) {
        if (locale != null)
            return gettext(locale, s);
        else
            return s;
    }
}
