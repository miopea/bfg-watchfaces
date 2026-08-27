import javax.xml.validation.*;
import javax.xml.transform.stream.StreamSource;
import org.xml.sax.*;
import java.io.File;

/**
 * Standalone WFF schema validator, used by build.sh and reskin.sh.
 *
 * The WFF schemas are XSD 1.1. The JDK's built-in validator only implements
 * 1.0, so this needs the Xerces + XPath2 jars that scripts/bootstrap.sh
 * fetches into tools/libs. The factory is named explicitly because JAXP
 * service discovery does not reliably find it and fails with an opaque
 * IllegalArgumentException.
 *
 * Compiled by scripts/bootstrap.sh. Usage:
 *   java -cp "tools:tools/libs/*" V tools/wff-schema/watchface.xsd res/raw/watchface.xml
 */
public class V {
  public static void main(String[] a) throws Exception {
    SchemaFactory f = SchemaFactory.newInstance(
        "http://www.w3.org/XML/XMLSchema/v1.1",
        "org.apache.xerces.jaxp.validation.XMLSchema11Factory",
        null);
    Schema s = f.newSchema(new File(a[0]));
    Validator v = s.newValidator();
    final int[] n = {0};
    v.setErrorHandler(new ErrorHandler() {
      public void warning(SAXParseException e){ System.out.println("WARN  line "+e.getLineNumber()+": "+e.getMessage()); }
      public void error(SAXParseException e){ n[0]++; System.out.println("ERROR line "+e.getLineNumber()+": "+e.getMessage()); }
      public void fatalError(SAXParseException e){ n[0]++; System.out.println("FATAL line "+e.getLineNumber()+": "+e.getMessage()); }
    });
    v.validate(new StreamSource(new File(a[1])));
    if (n[0] == 0) {
      System.out.println("\n*** VALID against the WFF schema ***");
    } else {
      System.out.println("\n" + n[0] + " error(s)");
      System.exit(1);
    }
  }
}
