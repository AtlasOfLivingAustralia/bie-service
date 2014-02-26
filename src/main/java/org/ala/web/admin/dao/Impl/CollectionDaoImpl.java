package org.ala.web.admin.dao.Impl;

import org.ala.dao.IndexedTypes;
import org.ala.dao.SolrUtils;
import org.ala.util.ReadOnlyLock;
import org.ala.util.WebUtils;
import org.ala.web.admin.dao.CollectionDao;
import org.apache.commons.httpclient.HttpClient;
import org.apache.commons.httpclient.methods.GetMethod;
import org.apache.log4j.Logger;
import org.apache.solr.client.solrj.SolrServer;
import org.apache.solr.common.SolrInputDocument;
import org.codehaus.jackson.annotate.JsonAnyGetter;
import org.codehaus.jackson.annotate.JsonAnySetter;
import org.codehaus.jackson.annotate.JsonCreator;
import org.codehaus.jackson.annotate.JsonProperty;
import org.codehaus.jackson.map.ObjectMapper;
import org.codehaus.jackson.type.TypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestOperations;
import org.springframework.web.client.RestTemplate;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.inject.Inject;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.InputStream;
import java.util.*;

@Component("collectionDao")
public class CollectionDaoImpl implements CollectionDao {
    protected String collectoryUriPrefix = "http://collections.ala.org.au";
    protected String baseUrlForCollectory = "http://collections.ala.org.au/public/show/";

    @Inject
	protected SolrUtils solrUtils;
    
    /** Spring injected RestTemplate object */   
    RestOperations restTemplate; // NB MappingJacksonHttpMessageConverter() injected by Spring
   
    /** Log4J logger */
    private final static Logger logger = Logger.getLogger(CollectionDaoImpl.class);

	private SolrServer solrServer = null;

    public CollectionDaoImpl(RestTemplate restTemplate){
    	this.restTemplate = restTemplate;
    }
    
    public CollectionDaoImpl(){}
    
    private Object callJsonWs(String jsonUri, Class clazz) throws Exception {
        String json = WebUtils.getUrlContentAsString(jsonUri);
        ObjectMapper om = new ObjectMapper();
        return om.readValue(json, clazz);
	}
    
    private String listToString(Object list){
    	String str = "";
    	if(list != null){
    		if(list instanceof List){   	
	    		String tmp = Arrays.toString(((List)list).toArray());
	    		tmp = tmp.replaceAll("\\[", "");
	    		tmp = tmp.replaceAll("\\]", "");
	    		tmp = tmp.replaceAll("\\{", "");
	    		tmp = tmp.replaceAll("\\}", "");
	    		str = tmp.trim();
	    	}
	    	else if (list instanceof String ){
	    		str = ((String) list).trim();
	    	}
    	}
    	return str;    	
    }
    
    public boolean reloadLayers() throws Exception {

        SolrServer solrServer = solrUtils.getSolrServer();
        solrServer.deleteByQuery("idxtype:"+IndexedTypes.LAYERS); // delete layers!

        HttpClient httpClient = new HttpClient();
        GetMethod gm = new GetMethod("http://spatial.ala.org.au/layers-service/layers.json");
        logger.debug("Response code for get method: " +httpClient.executeMethod(gm));
        String layerJson = gm.getResponseBodyAsString();
        ObjectMapper om = new ObjectMapper();
        List<Map<String,Object>> layers = om.readValue(layerJson, new TypeReference<List<Map<String,Object>>>() {});
        for(Map<String,Object> layer: layers){
            SolrInputDocument doc = new SolrInputDocument();
            doc.addField("name", layer.get("displayname"));
            doc.addField("guid", "http://spatial.ala.org.au/layers/more/"+layer.get("name"));
            doc.addField("description", layer.get("notes"));
            doc.addField("text", layer.get("source"));
            doc.addField("text", layer.get("type"));
            doc.addField("text", layer.get("notes"));
            doc.addField("text", layer.get("keywords"));
            if(layer.get("classification1") !=null) doc.addField("text", layer.get("classification1"));
            if(layer.get("classification2") !=null) doc.addField("text", layer.get("classification2"));
            doc.addField("content", layer.get("notes"));
            doc.addField("dataProviderName", layer.get("source"));
            doc.addField("url", "http://spatial.ala.org.au/layers/more/"+layer.get("name"));
            doc.addField("id", layer.get("id"));
            doc.addField("idxtype", IndexedTypes.LAYERS);
            doc.addField("australian_s", "recorded"); // so they appear in default QF search
            solrServer.add(doc);
        }
        logger.info("Finished syncing layer information with the collectory.");
        solrServer.commit();
        logger.info("Finished syncing layers.");
        return true;
    }
    
	private String getTagValue(String sTag, Element eElement) {
		NodeList nl = eElement.getElementsByTagName(sTag);
		if(nl == null || nl.getLength() < 1){
			return null;
		}
		
		NodeList nlList = nl.item(0).getChildNodes();	 
	    Node nValue = (Node) nlList.item(0);	 
	    return nValue.getNodeValue();
	}    
    
	public boolean reloadRegions() throws Exception {
		int ctr = 0;
		logger.info("Started syncing regions information....");
		
		// init....
		HttpClient httpClient = new HttpClient();
		GetMethod gm = new GetMethod("http://regions.ala.org.au/data/sitemap.xml");
		logger.debug("Response code for get method: " +httpClient.executeMethod(gm));        
		InputStream responseStream = gm.getResponseBodyAsStream();
		
        DocumentBuilderFactory dbFactory = DocumentBuilderFactory.newInstance();
		dbFactory.setNamespaceAware(false);

		DocumentBuilder parser = dbFactory.newDocumentBuilder();
		Document document = parser.parse(responseStream);
		NodeList nList = document.getElementsByTagName("url");
		
		// start process....
		if(nList.getLength() > 0){
			SolrServer solrServer = solrUtils.getSolrServer();
			solrServer.deleteByQuery("idxtype:"+IndexedTypes.REGION);
			for (int temp = 0; temp < nList.getLength(); temp++) {	 
				Node nNode = nList.item(temp);
				if (nNode.getNodeType() == Node.ELEMENT_NODE) {	 
					Element eElement = (Element) nNode;
					String loc = getTagValue("loc", eElement);
					if(loc != null){
						String url = java.net.URLDecoder.decode(loc, "UTF-8");
						String[] terms = url.split("/");
						if(terms.length > 1){
							String name = terms[terms.length -1];
							String region = terms[terms.length - 2];
							if(name != null && region != null){
								SolrInputDocument doc = new SolrInputDocument();
								doc.addField("idxtype", IndexedTypes.REGION);
								doc.addField("guid", url);
								doc.addField("id", url);
								doc.addField("url", url);
								doc.addField("regionType", region);
								doc.addField("name", name);
                                doc.addField("text", name);
                                doc.addField("australian_s", "recorded"); // so they appear in default QF search
								solrServer.add(doc);
								ctr++;
							}						
						}
					}			      
				}
			}
			solrServer.commit();
		}
		logger.info("Finished syncing regions information. Total count: " + ctr);
		return true;
	}
    
    public boolean reloadCollections() throws Exception{
    	Calendar ticket = null;
    	boolean completed = false;
    	int ctr = 0;

		if(solrServer == null){
			solrServer = solrUtils.getSolrServer();
		}
    	try{
	    	if(!ReadOnlyLock.getInstance().isReadOnly() && solrServer != null){
	    		ticket = Calendar.getInstance();
				if(ReadOnlyLock.getInstance().setLock(ticket)){	
					logger.info("**** setLock-isReadOnly: " + ReadOnlyLock.getInstance().isReadOnly());
					solrServer.deleteByQuery("idxtype:"+IndexedTypes.COLLECTION);
					solrServer.commit();
					
			    	List<Map> list = (List<Map>) callJsonWs(collectoryUriPrefix + "/ws/collection.json", java.util.List.class);
			    	Iterator<Map> it = list.iterator();
			    	while (it.hasNext()){
			    		try{
			    			ctr++;
				    		Map<String, String> map = it.next();
				    		String uri = map.get("uri");
				    		DynaBean dyna = (DynaBean) callJsonWs(uri, DynaBean.class);
				    		String uid = dyna.uid;
				    		String name = dyna.name;					    		
				    		String acronym = (String)(dyna.other.get("acronym") != null?dyna.other.get("acronym"):"");					    		
				    		String description = (String)(dyna.other.get("pubDescription") != null?dyna.other.get("pubDescription"):"");
				    		String subCollections = listToString(dyna.other.get("subCollections"));
				    		String keywords = listToString(dyna.other.get("keywords"));
				    		String collectionType = listToString(dyna.other.get("collectionType"));
				    					
							SolrInputDocument doc = new SolrInputDocument();
							doc.addField("acronym", acronym, 1.2f);
							doc.addField("name", name, 1.2f);
							doc.addField("guid", baseUrlForCollectory+uid);
							
							doc.addField("otherGuid", uid); // the internal UID e.g. co1
							if(dyna.other.get("guid")!=null){
								doc.addField("otherGuid", (String)dyna.other.get("guid")); // the external GUID e.g. url:lsid:bci:123
							}
							
							//add as text
							doc.addField("text", description);
							doc.addField("text", subCollections);
							doc.addField("text", keywords);
							doc.addField("text", collectionType);
							
							doc.addField("url", baseUrlForCollectory+uid);
							doc.addField("id", baseUrlForCollectory+uid);
							doc.addField("idxtype", IndexedTypes.COLLECTION);
							doc.addField("australian_s", "recorded"); // so they appear in default QF search
				
							solrServer.add(doc);
			    		} catch(Exception e){
			    			logger.error(e.getMessage(), e);
			    		}
			    	}    	
			    	solrServer.commit();
					completed = true;
				}
			}
			else{
				logger.info("**** isReadOnly: " + ReadOnlyLock.getInstance().isReadOnly());
			}
			logger.info("reloadCollections in (sec): "+((Calendar.getInstance().getTimeInMillis() - ticket.getTimeInMillis())/1000) + " , ctr = " + ctr);			
		}
		finally{
			if(ReadOnlyLock.getInstance().isReadOnly()){
				ReadOnlyLock.getInstance().setUnlock(ticket);
				logger.info("**** setUnlock-isReadOnly: " + ReadOnlyLock.getInstance().isReadOnly());
			}			
		}
    	return completed;
    }    
    
    public boolean reloadInstitutions() throws Exception{
    	Calendar ticket = null;
    	boolean completed = false;
    	int ctr = 0;
		
    	if(solrServer == null){
			solrServer = solrUtils.getSolrServer();
		}   	
    	try{
	    	if(!ReadOnlyLock.getInstance().isReadOnly() && solrServer != null){
	    		ticket = Calendar.getInstance();
				if(ReadOnlyLock.getInstance().setLock(ticket)){	
					logger.info("**** setLock-isReadOnly: " + ReadOnlyLock.getInstance().isReadOnly());
					solrServer.deleteByQuery("idxtype:"+IndexedTypes.INSTITUTION); // delete institutions!
					solrServer.commit();
					
			    	List<Map> list = (List<Map>) callJsonWs(collectoryUriPrefix + "/ws/institution.json", java.util.List.class);
			    	Iterator<Map> it = list.iterator();
			    	while (it.hasNext()){
			    		try{
			    			ctr++;
				    		Map<String, String> map = it.next();
				    		String uri = map.get("uri");
                            logger.debug("Calling : " + uri);

                            //System.out.println(WebUtils.getUrlContentAsString(uri));
                            String json = WebUtils.getUrlContentAsString(uri);
                            ObjectMapper om = new ObjectMapper();
                            om.readValue(json, DynaBean.class);
//				    		DynaBean dyna = (DynaBean) callJsonWs(uri, DynaBean.class);
                            DynaBean dyna = (DynaBean) om.readValue(json, DynaBean.class);
				    		String uid = dyna.uid;
				    		String name = dyna.name;					    		
				    		String acronym = (String)(dyna.other.get("acronym") != null?dyna.other.get("acronym"):"");					    		
				    		String description = (String)(dyna.other.get("pubDescription") != null?dyna.other.get("pubDescription"):"");
				    		String institutionType = (String)(dyna.other.get("institutionType") != null?dyna.other.get("institutionType"):"");
				    					
							SolrInputDocument doc = new SolrInputDocument();
							doc.addField("acronym", acronym, 1.2f);
							doc.addField("name", name, 1.2f);
							doc.addField("guid", baseUrlForCollectory+uid);
							
							if(dyna.other.get("guid")!=null){
								doc.addField("otherGuid", (String)dyna.other.get("guid")); // the external GUID e.g. url:lsid:bci:123
							}
							
							//add as text
							doc.addField("text", description);
							doc.addField("institutionType", institutionType);
							
							doc.addField("url", baseUrlForCollectory+uid);
							doc.addField("id", baseUrlForCollectory+uid);
							doc.addField("australian_s", "recorded"); // so they appear in default QF search
							doc.addField("idxtype", IndexedTypes.INSTITUTION);
							solrServer.add(doc);																
			    		} catch(Exception e){
			    			logger.error(e.getMessage(), e);
			    		}
			    	}    	
			    	solrServer.commit();
					completed = true;
				}
			} else {
				logger.info("**** isReadOnly: " + ReadOnlyLock.getInstance().isReadOnly());
			}
			logger.info("reloadInstitutions in (sec): "+((Calendar.getInstance().getTimeInMillis() - ticket.getTimeInMillis())/1000) + " , ctr = " + ctr);			
		} finally {
			if(ReadOnlyLock.getInstance().isReadOnly()){
				ReadOnlyLock.getInstance().setUnlock(ticket);
				logger.info("**** setUnlock-isReadOnly: " + ReadOnlyLock.getInstance().isReadOnly());
			}			
		}
    	return completed;
    }    
    
	public boolean reloadDataProviders()  throws Exception {
    	Calendar ticket = null;
    	boolean completed = false;
    	int ctr = 0;
 
		if(solrServer == null){
			solrServer = solrUtils.getSolrServer();
		}
    	try{
	    	if(!ReadOnlyLock.getInstance().isReadOnly() && solrServer != null){
	    		ticket = Calendar.getInstance();
				if(ReadOnlyLock.getInstance().setLock(ticket)){	
					logger.info("**** setLock-isReadOnly: " + ReadOnlyLock.getInstance().isReadOnly());
					solrServer.deleteByQuery("idxtype:"+IndexedTypes.DATAPROVIDER);
					solrServer.commit();
					
			    	List<Map> list = (List<Map>) callJsonWs(collectoryUriPrefix + "/ws/dataProvider.json", java.util.List.class);
			    	Iterator<Map> it = list.iterator();
			    	while (it.hasNext()){
			    		try{
			    			ctr++;
				    		Map<String, String> map = it.next();
				    		String uri = map.get("uri");
				    		DynaBean dyna = (DynaBean) callJsonWs(uri, DynaBean.class);
				    		String uid = dyna.uid;
				    		String name = dyna.name;					    		
				    		String description = (String)(dyna.other.get("pubDescription") != null?dyna.other.get("pubDescription"):"");
				    					
							SolrInputDocument doc = new SolrInputDocument();
							doc.addField("name", name);
							doc.addField("guid", baseUrlForCollectory+uid);
							
							doc.addField("description", description);								
							doc.addField("url", baseUrlForCollectory+uid);
							doc.addField("id", baseUrlForCollectory+uid);
							doc.addField("australian_s", "recorded"); // so they appear in default QF search
							doc.addField("idxtype", IndexedTypes.DATAPROVIDER);
							solrServer.add(doc);									
			    		}
			    		catch(Exception e){
			    			logger.error(e.getMessage(), e);
			    		}
			    	}    	
			    	solrServer.commit();
					completed = true;
				}
			}
			else{
				logger.info("**** isReadOnly: " + ReadOnlyLock.getInstance().isReadOnly());
			}
			logger.info("reloadDataProviders in (sec): "+((Calendar.getInstance().getTimeInMillis() - ticket.getTimeInMillis())/1000) + " , ctr = " + ctr);			
		}
		finally{
			if(ReadOnlyLock.getInstance().isReadOnly()){
				ReadOnlyLock.getInstance().setUnlock(ticket);
				logger.info("**** setUnlock-isReadOnly: " + ReadOnlyLock.getInstance().isReadOnly());
			}			
		}
    	return completed;
	}
	
	public boolean reloadDataResources()  throws Exception{
    	Calendar ticket = null;
    	boolean completed = false;
    	int ctr = 0;
 
		if(solrServer == null){
			solrServer = solrUtils.getSolrServer();
		}
    	try{
	    	if(!ReadOnlyLock.getInstance().isReadOnly() && solrServer != null){
	    		ticket = Calendar.getInstance();
				if(ReadOnlyLock.getInstance().setLock(ticket)){	
					logger.info("**** setLock-isReadOnly: " + ReadOnlyLock.getInstance().isReadOnly());
					solrServer.deleteByQuery("idxtype:"+IndexedTypes.DATASET);
					solrServer.commit();
					
			    	List<Map> list = (List<Map>) callJsonWs(collectoryUriPrefix + "/ws/dataResource.json", java.util.List.class);
			    	Iterator<Map> it = list.iterator();
			    	while (it.hasNext()){
			    		try{
			    			ctr++;
				    		Map<String, String> map = it.next();
				    		String uri = map.get("uri");
				    		DynaBean dyna = (DynaBean) callJsonWs(uri, DynaBean.class);
				    		String uid = dyna.uid;
				    		String name = dyna.name;					    		
				    		String acronym = (String)(dyna.other.get("acronym") != null?dyna.other.get("acronym"):"");					    		
				    		String description = (String)(dyna.other.get("pubDescription") != null?dyna.other.get("pubDescription"):"");
				    		Object o = dyna.other.get("provider");					    		
				    					
							SolrInputDocument doc = new SolrInputDocument();
							doc.addField("acronym", acronym);
							doc.addField("name", name);
							doc.addField("guid", baseUrlForCollectory+uid);
							
							if(dyna.other.get("guid")!=null){
								doc.addField("otherGuid", (String)dyna.other.get("guid")); // the external GUID e.g. url:lsid:bci:123
							}
							
							String dataProviderName = "";
			    			if(o != null && o instanceof Map){
			    				Map provider = (Map)o;
			    				if(provider.get("name") != null){
			    					dataProviderName = ((String)provider.get("name")).trim();				    					
			    				}
			    			}
			    			doc.addField("dataProviderName", dataProviderName);
							doc.addField("description", description);
							doc.addField("url", baseUrlForCollectory+uid);
							doc.addField("id", baseUrlForCollectory+uid);
							doc.addField("australian_s", "recorded"); // so they appear in default QF search
							doc.addField("idxtype", IndexedTypes.DATASET);
							solrServer.add(doc);									
			    		} catch(Exception e){
			    			logger.error(e.getMessage(), e);
			    		}
			    	}    	
			    	solrServer.commit();
					completed = true;
				}
			}
			else{
				logger.info("**** isReadOnly: " + ReadOnlyLock.getInstance().isReadOnly());
			}
			logger.info("reloadDataResources in (sec): "+((Calendar.getInstance().getTimeInMillis() - ticket.getTimeInMillis())/1000) + " , ctr = " + ctr);			
		}
		finally {
			if(ReadOnlyLock.getInstance().isReadOnly()){
				ReadOnlyLock.getInstance().setUnlock(ticket);
				logger.info("**** setUnlock-isReadOnly: " + ReadOnlyLock.getInstance().isReadOnly());
			}			
		}
    	return completed;
	}
    
}

class DynaBean {
    // Two mandatory properties
    protected final String uid;
    protected final String name;

    // and then "other" stuff:
    protected Map<String,Object> other = new HashMap<String,Object>();

    // Could alternatively add setters, but since these are mandatory
    @JsonCreator
    public DynaBean(@JsonProperty("uid") String uid, @JsonProperty("name") String name)
    {
        this.uid = uid;
        this.name = name;
    }

    public String getId() { return uid; }
    public String getName() { return name; }

    public Object get(String name) {
        return other.get(name);
    }

    // "any getter" needed for serialization    
    @JsonAnyGetter
    public Map<String,Object> any() {
        return other;
    }

    @JsonAnySetter
    public void set(String name, Object value) {
        other.put(name, value);
    }
}    
