package run.ikaros.jellyfin;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.Assert;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerConfigurationException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.File;

public class XmlUtils {
    private static final Logger LOGGER = LoggerFactory.getLogger(XmlUtils.class);

    public static String generateJellyfinTvShowNfoXml(String filePath, String plot,
                                                      String title,
                                                      String originalTitle,
                                                      String subjectId) {
        Assert.hasText(filePath, "'filePath' must has text.");

        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        DocumentBuilder db = null;
        try {
            db = factory.newDocumentBuilder();
        } catch (ParserConfigurationException e) {
            throw new RuntimeException("new document builder fail", e);
        }
        Document document = db.newDocument();
        // 不显示standalone="no"
        document.setXmlStandalone(true);
        Element tvshowElement = document.createElement("tvshow");
        document.appendChild(tvshowElement);

        Element plotElement = document.createElement("plot");
        plotElement.setTextContent(plot);
        tvshowElement.appendChild(plotElement);

        Element lockdataElement = document.createElement("lockdata");
        lockdataElement.setTextContent("false");
        tvshowElement.appendChild(lockdataElement);

        Element titleElement = document.createElement("title");
        titleElement.setTextContent(title);
        tvshowElement.appendChild(titleElement);

        Element originaltitleElement = document.createElement("originaltitle");
        originaltitleElement.setTextContent(originalTitle);
        tvshowElement.appendChild(originaltitleElement);

        Element bangumiidElement = document.createElement("bangumiid");
        bangumiidElement.setTextContent(subjectId);
        tvshowElement.appendChild(bangumiidElement);

        File file = new File(filePath);
        File dir = file.getParentFile();
        if (!dir.exists()) {
            dir.mkdirs();
        }

        try {
            TransformerFactory tff = TransformerFactory.newInstance();
            Transformer tf = tff.newTransformer();
            // 输出内容是否使用换行
            tf.setOutputProperty(OutputKeys.INDENT, "yes");
            // 创建xml文件并写入内容
            tf.transform(new DOMSource(document), new StreamResult(file));
            LOGGER.info("generate jellyfin tv show nfo xml file success, filePath: {}", filePath);
        } catch (TransformerConfigurationException transformerException) {
            LOGGER.warn("generate jellyfin tv show nfo xml file fail", transformerException);
        } catch (javax.xml.transform.TransformerException e) {
            throw new RuntimeException(e);
        }
        return filePath;
    }

    public static String generateJellyfinEpisodeNfoXml(String filePath, String plot,
                                                       String title, String season,
                                                       String episode, String subjectId) {
        Assert.hasText(filePath, "'filePath' must has text.");

        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        DocumentBuilder db = null;
        try {
            db = factory.newDocumentBuilder();
        } catch (ParserConfigurationException e) {
            throw new RuntimeException("new document builder fail", e);
        }
        Document document = db.newDocument();
        // 不显示standalone="no"
        document.setXmlStandalone(true);
        Element tvshowElement = document.createElement("episodedetails");
        document.appendChild(tvshowElement);

        Element plotElement = document.createElement("plot");
        plotElement.setTextContent(plot);
        tvshowElement.appendChild(plotElement);

        Element lockdataElement = document.createElement("lockdata");
        lockdataElement.setTextContent("false");
        tvshowElement.appendChild(lockdataElement);

        Element titleElement = document.createElement("title");
        titleElement.setTextContent(title);
        tvshowElement.appendChild(titleElement);

        Element seasonElement = document.createElement("season");
        seasonElement.setTextContent(season);
        tvshowElement.appendChild(seasonElement);

        Element episodeElement = document.createElement("episode");
        episodeElement.setTextContent(episode);
        tvshowElement.appendChild(episodeElement);

        Element bangumiidElement = document.createElement("bangumiid");
        bangumiidElement.setTextContent(subjectId);
        tvshowElement.appendChild(bangumiidElement);

        File file = new File(filePath);
        File dir = file.getParentFile();
        if (!dir.exists()) {
            dir.mkdirs();
        }

        try {
            TransformerFactory tff = TransformerFactory.newInstance();
            Transformer tf = tff.newTransformer();
            // 输出内容是否使用换行
            tf.setOutputProperty(OutputKeys.INDENT, "yes");
            // 创建xml文件并写入内容
            tf.transform(new DOMSource(document), new StreamResult(file));
            LOGGER.info("generate jellyfin episode nfo xml file success, filePath: {}", filePath);
        } catch (TransformerConfigurationException e) {
            throw new RuntimeException(e);
        } catch (javax.xml.transform.TransformerException e) {
            throw new RuntimeException(e);
        }
        return filePath;
    }
}
