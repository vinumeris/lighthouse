package lighthouse.utils;

import org.jetbrains.annotations.*;
import org.slf4j.*;

import javax.annotation.Nullable;
import java.util.*;

import static gnu.gettext.GettextResource.*;

public class I18nUtil {
    private static final Logger log = LoggerFactory.getLogger(I18nUtil.class);
    @Nullable private static ResourceBundle locale;

    public static ResourceBundle translations = new ResourceBundle() {
        @Override
        protected Object handleGetObject(String key) {
            if (locale == null)
                return key;
            else
                try {
                    return locale.getObject(key);
                } catch (MissingResourceException e) {
                    return key;
                }
        }

        @Override
        public boolean containsKey(String key) {
            return true;
        }

        @NotNull
        @Override
        public Enumeration<String> getKeys() {
            if (locale != null)
                return locale.getKeys();
            else
                return Collections.emptyEnumeration();
        }
    };

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
