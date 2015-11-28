import groovy.util.slurpersupport.GPathResult;

import javax.xml.XMLConstants;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;
import javax.xml.validation.Validator;
import javax.xml.transform.Source;
import javax.xml.transform.stream.StreamSource;

import org.apache.commons.io.IOUtils;
import org.apache.commons.io.output.ByteArrayOutputStream;
import org.xml.sax.SAXException;

import java.util.zip.GZIPInputStream;
import java.net.URL;

URL url = new URL(p_url);
if (url.toExternalForm().endsWith(".gz") || url.toExternalForm().endsWith(".xml")){
   logger.info("Find sitemap {}", url);
} else if (url.getPath() == "/" || url.getPath() == "") {
   String robotsUrl = url.toExternalForm() + (url.getPath() == ""?"/":"") + "robots.txt";
   logger.info("Reading {}",robotsUrl);
   String robots = new URL(robotsUrl).getText("UTF-8");
   robots.split('\n').each{
       if (it.startsWith("SITEMAP:")) {
           url = new URL(it.substring("SITEMAP:".length()).trim());
           logger.info("Find sitemap {}", url);
       }
   }
   // TODO validate link
} else {
   logger.error("Can not understand url {}",url);
   return;
}

InputStream is = url.openStream();
dbm.closeResourceOnExit(is);
if (url.toExternalForm().endsWith(".gz")) {
    is = new GZIPInputStream(is);
}

ByteArrayOutputStream output = new ByteArrayOutputStream();
int count = 0;
int n = 0;
byte[] buffer = new byte[4096];
while (-1 != (n = is.read(buffer))) {
    output.write(buffer, 0, n);
    count += n;
    if (n > 11 * 1024 * 1024) {
        logger.error("File length is more then 11 mb ({} bytes)");
        return;
    }
}
byte[] rawXml = output.toByteArray();
if (rawXml.length > 10 * 1024 * 1024) {
    logger.warn("Size exceed 10 mb ({} bytes)", rawXml.length);
}

GPathResult gpr = new XmlSlurper(true, false, false).parse(new ByteArrayInputStream(rawXml));

if (gpr.@'xmlns' != "http://www.sitemaps.org/schemas/sitemap/0.9") {
    logger.warn("XML name set should be xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\"");
}

if (gpr.@'xsi:schemaLocation' == null || !gpr.@'xsi:schemaLocation'.toString().contains("http://www.sitemaps.org/schemas/sitemap/0.9/sitemap.xsd") 
     || gpr.@'xmlns:xsi' != "http://www.w3.org/2001/XMLSchema-instance") {
     logger.warn("XSD schema is not set properly, force validation");
     
     URL schemaFile = new URL("http://www.sitemaps.org/schemas/sitemap/0.9/sitemap.xsd");
     Source xmlFile = new StreamSource(new ByteArrayInputStream(rawXml));
     SchemaFactory schemaFactory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
     Schema schema = schemaFactory.newSchema(schemaFile);
     Validator validator = schema.newValidator();
     try {
         validator.validate(xmlFile);
     } catch (SAXException e) {
         logger.warn("Sitemap.xml is not valid {}",e);
         return;
     }
}

String basePath;
if (url.getPath() == "/") {
    basePath = url.toExternalForm();
} else if (url.getPath() == "") {
    basePath = url.toExternalForm()+"/";
} else {
    basePath = url.toExternalForm().substring(0, url.toExternalForm().lastIndexOf('/')+1);
}
logger.debug("sitemap.xml relative path: {} -> {}",url, basePath);

int urlCount = 0;
gpr.url.each{ xmlUrl ->
    urlCount++;
    
    String loc = xmlUrl.loc.text();
    
    logger.info("Validation {}", loc);
    
    if (!loc.startsWith(basePath)) {
        logger.warn("URL {} is not start from {}", loc, basePath);
    }
    // TODO too old date check
    URL testUrl = new URL(loc);
    HttpURLConnection httpConnection = null;
    try {
        httpConnection = testUrl.openConnection();
        if (httpConnection.getResponseCode() != HttpURLConnection.HTTP_OK) {
            logger.warn("URL {} returns {}", loc, httpConnection.getResponseCode() );
        }
    } catch (Exception e) {
        logger.warn("URL {} throws {}", loc, e);
    } finally {
        if (httpConnection!=null) {
            httpConnection.disconnect();
        }
    }
};

if (urlCount > 50000) {
    logger.warn("urls more then 50 000 -> {}", urlCount);
}

logger.info("{} urls analyzed", urlCount);

