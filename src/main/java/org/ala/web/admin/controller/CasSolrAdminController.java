package org.ala.web.admin.controller;

import java.io.PrintWriter;
import java.util.Set;

import javax.inject.Inject;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.ala.dao.RankingDao;
import org.ala.dao.SolrUtils;
import org.ala.dao.SolrUtils.IndexFieldDTO;
import org.ala.harvester.BiocacheHarvester;
import org.ala.hbase.RepoDataLoader;
import org.ala.lucene.CreateWordPressIndex;
import org.ala.report.GoogleSitemapGenerator;
import org.ala.util.ReadOnlyLock;
import org.ala.util.XmlReportUtil;
import org.ala.web.admin.dao.CollectionDao;
import org.apache.log4j.Logger;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;

@Controller
public class CasSolrAdminController {
	private static final String ADMIN_ROLE = "ROLE_SYSTEM_ADMIN";
	
	/** Logger initialisation */
    private final static Logger logger = Logger.getLogger(CasSolrAdminController.class);
    
	@Inject
	protected RankingDao rankingDao;
	
	@Inject
	protected CollectionDao collectionsDao;
	   
	@Inject
	protected GoogleSitemapGenerator googleSitemapGenerator;
	
	@Inject
	protected XmlReportUtil xmlReportUtil;
	
	@Inject
	protected BiocacheHarvester biocacheHarvester;

	@Inject
	protected CreateWordPressIndex createWordPressIndex;
	
	@Inject
	protected RepoDataLoader repoDataLoader; 
	
	@Inject
    protected SolrUtils solrUtils;
	/**
	 * Returns true when in service is in readonly mode.
	 * 
	 * @return
	 */
	@RequestMapping(value = {"/ws/admin/isReadOnly","/admin/isReadOnly"}, method = RequestMethod.GET)
	public @ResponseBody
	boolean isReadOnly() {
		return ReadOnlyLock.getInstance().isReadOnly();
	}

	/**
	 * Returns true when in service is in readonly mode.
	 * 
	 * @return
	 */
	@RequestMapping(value = {"/admin/forceUnlock/{password}"}, method = RequestMethod.GET)
	public @ResponseBody
	boolean forceUnlock(@PathVariable("password") String password, HttpServletRequest request) {
		boolean completed = false;
		String remoteuser = request.getRemoteUser();
		if (remoteuser != null && request.isUserInRole(ADMIN_ROLE)) {	
			completed = ReadOnlyLock.getInstance().forceUnlock(password);
		}
		return completed;
	}
	
	/**
	 * Returns true when in service is in readonly mode.
	 * 
	 * @return
	 */
	@RequestMapping(value = {"/admin/syncBiocache"}, method = RequestMethod.GET)
	public @ResponseBody String syncBiocache(HttpServletRequest request, HttpServletResponse response) throws Exception {
		response.setContentType("application/json");
		String remoteuser = request.getRemoteUser();
		if (remoteuser != null && request.isUserInRole(ADMIN_ROLE)) {				
			syncBiocache();
			return "{\"status\":\"Started\"}";
		} else {
			response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
			return "{\"response\": \"You need to have the appropriate role (" + ADMIN_ROLE + ") to access this service. \"}";
		}
	}	
	
	private void syncBiocache() {
		Runnable r = new Runnable(){
			@Override
			public void run() {
            try {
                logger.info("Starting Biocache harvest.");
                //BiocacheHarvester bh = new BiocacheHarvester();
                biocacheHarvester.main(new String[]{"-lastWeek"});
                logger.info("Starting Biocache loading.");
                //RepoDataLoader rl = new RepoDataLoader();
                repoDataLoader.main(new String[]{"-reindex", "-biocache"});
                logger.info("Biocache synchronise complete.");
                solrUtils.reopenSolr();
                logger.info("Biocache synchronise complete - index reopened...");
            } catch(Exception e){
                logger.error(e.getMessage(), e);
            }
			}
		};
		Thread t = new Thread(r);
		t.start();		
	}
	
	/**
	 * Optimises the SOLR index. Use this API to optimise the index so that the
	 * bie-service can enter read only mode during this process.
	 * 
	 * @param request
	 * @param response
	 * @throws Exception
	 */
	@RequestMapping(value = "/admin/optimise", method = RequestMethod.GET)
	public void optimise(HttpServletRequest request,
			HttpServletResponse response) {
		String remoteuser = request.getRemoteUser();
		response.setContentType("application/json");
		boolean completed = false;
		PrintWriter writer = null;
		try{
			writer = response.getWriter();
			if (remoteuser != null && request.isUserInRole(ADMIN_ROLE)) {	
				completed = rankingDao.optimiseIndex();
				response.setStatus(HttpServletResponse.SC_OK);
				writer.write("{\"task_completed\": " + completed + "}");
			}
			else{
				response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
				writer.write("{\"response\": \"You need to have the appropriate role (" + ADMIN_ROLE + ") to access this service. task completed:" + completed + "\"}");
			}
		}
		catch(Exception ex){
			response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
			writer.write("{error: " + ex.getMessage() + "}");
			logger.error(ex);
		}		
	}


	@RequestMapping(value = "/admin/loadCaab", method = RequestMethod.GET)
	public void loadCaab(HttpServletRequest request,
			HttpServletResponse response) {
		String remoteuser = request.getRemoteUser();
		response.setContentType("application/json");
		boolean completed = false;
		PrintWriter writer = null;
		
		try{
			writer = response.getWriter();
			if (remoteuser != null && request.isUserInRole(ADMIN_ROLE)) {				
				completed = rankingDao.loadCAAB();
				response.setStatus(HttpServletResponse.SC_OK);
				writer.write("{\"task_completed\": " + completed + "}");
			}
			else{
				response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
				writer.write("{\"response\": \"You need to have the appropriate role (" + ADMIN_ROLE + ") to access this service. task completed:" + completed + "\"}");
			}
		}
		catch(Exception ex){
			response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
			writer.write("{error: " + ex.getMessage() + "}");
			logger.error(ex);
		}		
	}

    @RequestMapping(value = "/admin/reloadAllRanks", method = RequestMethod.GET)
    public void reloadAllRanks(HttpServletRequest request, 
            HttpServletResponse response) throws Exception{
    	String remoteuser = request.getRemoteUser();
    	response.setContentType("application/json");
		boolean completed = false;
		PrintWriter writer = null;
		try{
			writer = response.getWriter();
			if (remoteuser != null && request.isUserInRole(ADMIN_ROLE)) {
				completed = rankingDao.reloadAllRanks();
				response.setStatus(HttpServletResponse.SC_OK);
				writer.write("{\"task_completed\": " + completed + "}");
			}
			else{
				response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
				writer.write("{\"response\": \"You need to have the appropriate role (" + ADMIN_ROLE + ") to access this service. task completed:" + completed + "\"}");
			}
		}
		catch(Exception ex){
			response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
			writer.write("{error: " + ex.getMessage() + "}");
			logger.error(ex);
		}		
    }	
    
    @RequestMapping(value = "/admin/reloadCollections", method = RequestMethod.GET)
    public void reloadCollections(HttpServletRequest request, 
            HttpServletResponse response) throws Exception{
    	String remoteuser = request.getRemoteUser();
    	response.setContentType("application/json");
		boolean completed = false;
		PrintWriter writer = null;
		try{
			writer = response.getWriter();
			if (remoteuser != null && request.isUserInRole(ADMIN_ROLE)) {
				completed = collectionsDao.reloadCollections();
				response.setStatus(HttpServletResponse.SC_OK);
				writer.write("{\"task_completed\": " + completed + "}");
			}
			else{
				response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
				writer.write("{\"response\": \"You need to have the appropriate role (" + ADMIN_ROLE + ") to access this service. task completed:" + completed + "\"}");
			}
		}
		catch(Exception ex){
			response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
			writer.write("{error: " + ex.getMessage() + "}");
			logger.error(ex);
		}		
    }
    
    @RequestMapping(value = "/admin/reloadLayers", method = RequestMethod.GET)
    public void reloadLayers(HttpServletRequest request, 
            HttpServletResponse response) throws Exception{
    	String remoteuser = request.getRemoteUser();
    	response.setContentType("application/json");
		boolean completed = false;
		PrintWriter writer = null;
		try{
			writer = response.getWriter();
			if (remoteuser != null && request.isUserInRole(ADMIN_ROLE)) {
				completed = collectionsDao.reloadLayers();
				response.setStatus(HttpServletResponse.SC_OK);
				writer.write("{\"task_completed\": " + completed + "}");
			}
			else{
				response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
				writer.write("{\"response\": \"You need to have the appropriate role (" + ADMIN_ROLE + ") to access this service. task completed:" + completed + "\"}");
			}
		}
		catch(Exception ex){
			response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
			writer.write("{error: " + ex.getMessage() + "}");
			logger.error(ex);
		}		
    }    
    
    @RequestMapping(value = "/admin/reloadRegions", method = RequestMethod.GET)
    public void reloadRegions(HttpServletRequest request, 
            HttpServletResponse response) throws Exception{
    	String remoteuser = request.getRemoteUser();
    	response.setContentType("application/json");
		boolean completed = false;
		PrintWriter writer = null;
		try{
			writer = response.getWriter();
			if (remoteuser != null && request.isUserInRole(ADMIN_ROLE)) {
				completed = collectionsDao.reloadLayers();
				response.setStatus(HttpServletResponse.SC_OK);
				writer.write("{\"task_completed\": " + completed + "}");
			}
			else{
				response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
				writer.write("{\"response\": \"You need to have the appropriate role (" + ADMIN_ROLE + ") to access this service. task completed:" + completed + "\"}");
			}
		}
		catch(Exception ex){
			response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
			writer.write("{error: " + ex.getMessage() + "}");
			logger.error(ex);
		}		
    }    
    
    @RequestMapping(value = "/admin/reloadAll", method = RequestMethod.GET)
    public void reloadAlls(HttpServletRequest request, 
            HttpServletResponse response) throws Exception{
    	String remoteuser = request.getRemoteUser();
    	response.setContentType("application/json");
		boolean completed = true;
		PrintWriter writer = null;
		try{
			writer = response.getWriter();
			if (remoteuser != null && request.isUserInRole(ADMIN_ROLE)) {
				try { completed = completed && collectionsDao.reloadLayers(); } catch(Exception e) { completed = false; }
				try { completed = completed && collectionsDao.reloadRegions();  } catch(Exception e) { completed = false; }
				try { completed = completed && collectionsDao.reloadCollections();  } catch(Exception e) { completed = false; }
				try { completed = completed && collectionsDao.reloadDataProviders();  } catch(Exception e) { completed = false; }
				try { completed = completed && collectionsDao.reloadDataResources();  } catch(Exception e) { completed = false; }
				try { completed = completed && collectionsDao.reloadInstitutions();  } catch(Exception e) { completed = false; }
                try { completed = completed && createWordPressIndex.loadWordpress()>0;  } catch(Exception e) { completed = false; }
				response.setStatus(HttpServletResponse.SC_OK);
				writer.write("{\"task_completed\": " + completed + "}");
			}
			else {
				response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
				writer.write("{\"response\": \"You need to have the appropriate role (" + ADMIN_ROLE + ") to access this service. task completed:" + completed + "\"}");
			}
		}
		catch(Exception ex){
			response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
			writer.write("{error: " + ex.getMessage() + "}");
			logger.error(ex);
		}		
    }

    @RequestMapping(value = "/admin/reloadWordpress", method = RequestMethod.GET)
    public void reloadWordpress(HttpServletRequest request,
            HttpServletResponse response) throws Exception{
    	String remoteuser = request.getRemoteUser();
    	response.setContentType("application/json");
		boolean completed = true;
		PrintWriter writer = null;
		try{
			writer = response.getWriter();
			if (remoteuser != null && request.isUserInRole(ADMIN_ROLE)) {
                int pagesLoaded = createWordPressIndex.loadWordpress();
                logger.info("Crawled and indexed "+ pagesLoaded + " pages.");
				response.setStatus(HttpServletResponse.SC_OK);
				writer.write("{\"task_completed\": " + completed + "}");
			}
			else {
				response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
				writer.write("{\"response\": \"You need to have the appropriate role (" + ADMIN_ROLE + ") to access this service. task completed:" + completed + "\"}");
			}
		}
		catch(Exception ex){
			response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
			writer.write("{error: " + ex.getMessage() + "}");
			logger.error(ex);
		}
    }

    @RequestMapping(value="/admin/reopenIndex", method =RequestMethod.GET)
    public @ResponseBody String reopenIndex(HttpServletResponse response) throws Exception{
        //reopen the SOLR index to take advantage of terms that have been indexed external to the webapp.
        solrUtils.reopenSolr();
        response.setContentType("application/json");
        return "{\"status\":\"SOLR Server reopened\"}";
    }
    
    @RequestMapping(value="/admin/indexFields", method=RequestMethod.GET)
    public @ResponseBody Set<IndexFieldDTO> getIndexFields() throws Exception {
        return solrUtils.getIndexFieldDetails(null);
    }

    @RequestMapping(value = "/admin/reloadInstitutions", method = RequestMethod.GET)
    public void reloadInstitutions(HttpServletRequest request, 
            HttpServletResponse response) throws Exception{
        response.setContentType("application/json");
    	String remoteuser = request.getRemoteUser();
		boolean completed = false;
		PrintWriter writer = null;
		try{
			writer = response.getWriter();
			if (remoteuser != null && request.isUserInRole(ADMIN_ROLE)) {
				completed = collectionsDao.reloadInstitutions();
				response.setStatus(HttpServletResponse.SC_OK);
				writer.write("{\"task_completed\": " + completed + "}");
			}
			else{
				response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
				writer.write("{\"response\": \"You need to have the appropriate role (" + ADMIN_ROLE + ") to access this service. task completed:" + completed + "\"}");
			}
		}
		catch(Exception ex){
			response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
			writer.write("{\"error\":" + ex.getMessage() + "}");
			logger.error(ex);
		}		
    }	

    @RequestMapping(value = "/admin/reloadDataProviders", method = RequestMethod.GET)
    public void reloadDataProviders(HttpServletRequest request, 
            HttpServletResponse response)throws Exception{
        response.setContentType("application/json");
    	String remoteuser = request.getRemoteUser();
		boolean completed = false;
		PrintWriter writer = null;
		try{
			writer = response.getWriter();
			if (remoteuser != null && request.isUserInRole(ADMIN_ROLE)) {
				completed = collectionsDao.reloadDataProviders();
				response.setStatus(HttpServletResponse.SC_OK);
				writer.write("{\"task_completed\": " + completed + "}");
			}
			else{
				response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
				writer.write("{\"message\":\"You need to have the appropriate role (" + ADMIN_ROLE + ") to access this service. task completed:" + completed + "\"}");
			}
		}
		catch(Exception ex){
			response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
			writer.write("{\"error\": \"" + ex.getMessage() + "\"}");
			logger.error(ex);
		}		
    }	

    @RequestMapping(value = "/admin/reloadDataResources", method = RequestMethod.GET)
    public void reloadDataResources(HttpServletRequest request, 
            HttpServletResponse response)throws Exception{
        response.setContentType("application/json");
    	String remoteuser = request.getRemoteUser();
		boolean completed = false;
		PrintWriter writer = null;
		try{
			writer = response.getWriter();
			if (remoteuser != null && request.isUserInRole(ADMIN_ROLE)) {
				completed = collectionsDao.reloadDataResources();
				response.setStatus(HttpServletResponse.SC_OK);
				writer.write("{\"task_completed\": " + completed + "}");
			}
			else{
				response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
				writer.write("{\"message\":\"You need to have the appropriate role (" + ADMIN_ROLE + ") to access this service. task completed:" + completed + "\"}");
			}
		}
		catch(Exception ex){
			response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
			writer.write("{\"error\": " + ex.getMessage() + "}");
			logger.error(ex);
		}		
    }	
 
    @RequestMapping(value = "/admin/regenSitemap", method = RequestMethod.GET)
    public void regenSitemap(HttpServletRequest request, 
            HttpServletResponse response)throws Exception{
        response.setContentType("application/json");
    	String remoteuser = request.getRemoteUser();
		boolean completed = false;
		PrintWriter writer = null;
		try{
			writer = response.getWriter();
			if (remoteuser != null && request.isUserInRole(ADMIN_ROLE)) {
				googleSitemapGenerator.doFullScan();
				completed = true;				
				response.setStatus(HttpServletResponse.SC_OK);
				writer.write("{\"task_completed\": " + completed + "}");
			}
			else{
				response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
				writer.write("{\"message\":\"You need to have the appropriate role (" + ADMIN_ROLE + ") to access this service. task completed:" + completed + "\"}");
			}
		}
		catch(Exception ex){
			response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
			writer.write("{error: " + ex.getMessage() + "}");
			logger.error(ex);
		}		
    }	
    
	@RequestMapping(value = "/admin/xmlReport/{id}", method = RequestMethod.GET)
	public void xmlReport(@PathVariable("id") String id, HttpServletRequest request, HttpServletResponse response) {
		String remoteuser = request.getRemoteUser();
		PrintWriter writer = null;
		if (id != null && id.length() > 0) {				
			try {
				writer = response.getWriter();
				if(xmlReportUtil != null){
					response.setContentType("text/xml");
					xmlReportUtil.generateReport(id, writer);
				}
				else{
					response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
				}
				
			} catch (Exception e) {
				logger.error(e);
			}
		}
		else{
			response.setStatus(HttpServletResponse.SC_BAD_GATEWAY);
		}
	}

	public void setRankingDao(RankingDao rankingDao) {
		this.rankingDao = rankingDao;
	}

	public void setCollectionsDao(CollectionDao collectionsDao) {
		this.collectionsDao = collectionsDao;
	}

	public void setGoogleSitemapGenerator(
			GoogleSitemapGenerator googleSitemapGenerator) {
		this.googleSitemapGenerator = googleSitemapGenerator;
	}

	public void setXmlReportUtil(XmlReportUtil xmlReportUtil) {
		this.xmlReportUtil = xmlReportUtil;
	}

    public void setCreateWordPressIndex(CreateWordPressIndex createWordPressIndex) {
        this.createWordPressIndex = createWordPressIndex;
    }

    public void setBiocacheHarvester(BiocacheHarvester biocacheHarvester) {
		this.biocacheHarvester = biocacheHarvester;
	}

	public void setRepoDataLoader(RepoDataLoader repoDataLoader) {
		this.repoDataLoader = repoDataLoader;
	}

	public void setSolrUtils(SolrUtils solrUtils) {
		this.solrUtils = solrUtils;
	}   
}
