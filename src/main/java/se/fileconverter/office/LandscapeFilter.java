package se.fileconverter.office;

import com.sun.star.beans.XPropertySet;
import com.sun.star.container.XNameAccess;
import com.sun.star.lang.XComponent;
import com.sun.star.style.XStyleFamiliesSupplier;
import com.sun.star.uno.UnoRuntime;
import org.jodconverter.core.office.OfficeContext;
import org.jodconverter.local.filter.Filter;
import org.jodconverter.local.filter.FilterChain;

/**
 * Gotenbergs {@code landscape=true}: alla sidformat i dokumentet blir liggande. Gäller dokument
 * med sidformat (Writer, Calc); presentationer är redan liggande.
 */
final class LandscapeFilter implements Filter {

    @Override
    public void doFilter(OfficeContext context, XComponent document, FilterChain chain) throws Exception {
        XStyleFamiliesSupplier supplier = UnoRuntime.queryInterface(XStyleFamiliesSupplier.class, document);
        if (supplier != null && supplier.getStyleFamilies().hasByName("PageStyles")) {
            XNameAccess pageStyles = UnoRuntime.queryInterface(XNameAccess.class,
                    supplier.getStyleFamilies().getByName("PageStyles"));
            for (String name : pageStyles.getElementNames()) {
                XPropertySet style = UnoRuntime.queryInterface(XPropertySet.class, pageStyles.getByName(name));
                if (style == null || Boolean.TRUE.equals(style.getPropertyValue("IsLandscape"))) {
                    continue;
                }
                Object width = style.getPropertyValue("Width");
                Object height = style.getPropertyValue("Height");
                style.setPropertyValue("IsLandscape", Boolean.TRUE);
                if (((Number) width).intValue() < ((Number) height).intValue()) {
                    style.setPropertyValue("Width", height);
                    style.setPropertyValue("Height", width);
                }
            }
        }
        chain.doFilter(context, document);
    }
}
