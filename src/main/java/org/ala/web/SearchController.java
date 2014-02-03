/**************************************************************************
 *  Copyright (C) 2010 Atlas of Living Australia
 *  All Rights Reserved.
 *
 *  The contents of this file are subject to the Mozilla Public
 *  License Version 1.1 (the "License"); you may not use this file
 *  except in compliance with the License. You may obtain a copy of
 *  the License at http://www.mozilla.org/MPL/
 *
 *  Software distributed under the License is distributed on an "AS
 *  IS" basis, WITHOUT WARRANTY OF ANY KIND, either express or
 *  implied. See the License for the specific language governing
 *  rights and limitations under the License.
 ***************************************************************************/
package org.ala.web;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.inject.Inject;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.ala.dao.FulltextSearchDao;
import org.ala.dao.IndexedTypes;
import org.ala.dto.AutoCompleteDTO;
import org.ala.dto.FacetResultDTO;
import org.ala.dto.FieldResultDTO;
import org.ala.dto.SearchDTO;
import org.ala.dto.SearchResultsDTO;
import org.ala.dto.SearchTaxonConceptDTO;
import org.apache.commons.httpclient.HttpClient;
import org.apache.commons.httpclient.NameValuePair;
import org.apache.commons.httpclient.methods.GetMethod;
import org.apache.commons.lang.StringEscapeUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.log4j.Logger;
import org.apache.solr.client.solrj.util.ClientUtils;
//import org.apache.xalan.xsltc.compiler.Pattern;
import org.codehaus.jackson.map.DeserializationConfig;
import org.codehaus.jackson.map.ObjectMapper;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.ModelAndView;

/**
 * Search controller intended to provide the front door for the BIE.
 *
 * @author Dave Martin (David.Martin@csiro.au)
 */
@Controller("searchController")
public class SearchController {

	private final static Logger logger = Logger.getLogger(SearchController.class);
	
	/** DAO bean for SOLR search queries */
	@Inject
	private FulltextSearchDao searchDao;
	
	@Inject
	protected RepoUrlUtils repoUrlUtils;

    protected String biocacheServiceUrl="http://biocache.ala.org.au/ws";
	
	/** Name of view for list of taxa */
	private final String SEARCH_LIST = "search/list"; // "species/list" || "search/species"
    private final String AUTO_JSON = "search/autoJson";

    private String defaultDownloadFields="";
    private Properties properties;
    
    protected Pattern urnPattern = Pattern.compile("urn:[a-zA-Z0-9\\.:-]*");

    protected ObjectMapper mapper = new ObjectMapper();
    
    public SearchController(){
    	mapper.getDeserializationConfig().set(DeserializationConfig.Feature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    	//get the i18n map to use for download fields
    	properties = new Properties();
    	try {
    	    properties.load(getClass().getResourceAsStream("/messages.properties"));
    	}
    	catch(Exception e){
    	    logger.error(e.getMessage(),e);
    	}
    }

    private String prepareQuery(String query){
        Matcher matcher =urnPattern.matcher(query);
        StringBuffer sb = new StringBuffer();
        while(matcher.find()){
            matcher.appendReplacement(sb, ClientUtils.escapeQueryChars(matcher.group()).replaceAll("\\\\", "\\\\\\\\"));
        }
        matcher.appendTail(sb);
        query = sb.toString();        
        return query;
    }
    /*
     * A revised list of characters to escape in BIE SOLR searches.  We don't wish to escape the whitespace characters.
     */
    public static String bieEscapeQueryChars(String s) {
        if(s != null || "*".equals(s)){
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < s.length(); i++) {
              char c = s.charAt(i);
              // These characters are part of the query syntax and must be escaped
              if (c == '\\' || c == '+' || c == '-' || c == '!'  || c == '(' || c == ')' || c == ':'
                || c == '^' || c == '[' || c == ']' || c == '\"' || c == '{' || c == '}' || c == '~'
                || c == '*' || c == '?' || c == '|' || c == '&'  || c == ';' || c == '/'
                ) {
                sb.append('\\');
              }
              sb.append(c);
            }
            return sb.toString();
        } else{
            return s;
        }
      }
    

    /**
     * Downloads the supplied fields to the SOLR query. NB this adds an extra fq for idxtype:TAXON
     * @param query
     * @param filterQuery
     * @param filename
     * @param sortField
     * @param sortDirection
     * @param fields
     * @param response
     * @throws Exception
     */
    @RequestMapping(value="/download", method = RequestMethod.GET)
    public void downloadResults(
            @RequestParam(value="q", required=false) String query,
            @RequestParam(value="fq", required=false) String[] filterQuery,
            @RequestParam(value="file", required=false, defaultValue ="species_data.csv") String filename,
            @RequestParam(value="sort", required=false, defaultValue="score") String sortField,
            @RequestParam(value="dir", required=false, defaultValue ="asc") String sortDirection,
            @RequestParam(value="fields", required=false) String fields,
            HttpServletResponse response
            )throws Exception{
        
        response.setHeader("Cache-Control", "must-revalidate");
        response.setHeader("Pragma", "must-revalidate");
        response.setHeader("Content-Disposition", "attachment;filename=" + filename);
        response.setContentType("text/csv");
        
        //add the extra fq for idxtype=TAXON
        filterQuery = ArrayUtils.add(filterQuery, "idxtype:TAXON");
        
        query = prepareQuery(query);      
        if(StringUtils.isBlank(fields))
            fields = defaultDownloadFields;
        //reverse the sort direction for the "score" field a normal sort should be descending while a reverse sort should be ascending
        sortDirection = getSortDirection(sortField, sortDirection);
        java.io.OutputStream output = response.getOutputStream();
        String[] afields = fields.split(",");
        boolean first = true;
        byte[] tab = ",".getBytes();        
        for(String field:afields){
            String value = properties.getProperty(field, field);
            if(!first)
                output.write(tab);
            first = false;
            output.write(value.getBytes());
        }
        output.write("\n".getBytes());
        searchDao.writeResults(query, filterQuery, sortField, sortDirection, afields, output);
        output.flush();
        output.close();        
    }
	
	/**
	 * Performs a search across all objects, and selects to show the view for the closest match.
     *
     * Workflow should be:
     *
     * 1) Find exact match in Australia
     * 2) If 1) fails, Find exact match
     * 3) If 2) fails, Full search over everything
	 *
	 *
	 * NQ 2014-01-13: TODO review this code
	 *
	 * @param query
	 * @param filterQuery
	 * @param startIndex
	 * @param pageSize
	 * @param sortField
	 * @param sortDirection
	 * @param title
	 * @param model
	 * @return
	 * @throws Exception
	 */
	@RequestMapping(value = "/search", method = RequestMethod.GET)
	public String search(
			@RequestParam(value="q", required=false) String query,
			@RequestParam(value="fq", required=false) String[] filterQuery,
			@RequestParam(value="start", required=false, defaultValue="0") Integer startIndex,
			@RequestParam(value="pageSize", required=false, defaultValue ="10") Integer pageSize,
			@RequestParam(value="sort", required=false, defaultValue="score") String sortField,
			@RequestParam(value="dir", required=false, defaultValue ="asc") String sortDirection,
			@RequestParam(value="title", required=false, defaultValue ="Search Results") String title,
			@RequestParam(value="facets", required=false) String[] facets,
		    Model model) throws Exception {
		
        if (startIndex == null) {
            startIndex = 0;
        }
        if (pageSize == null) {
            pageSize = 20;
        }
        if (sortField.isEmpty()) {
            sortField = "score";
        }
        if (sortDirection.isEmpty()) {
            sortDirection = "asc";
        }

        //reverse the sort direction for the "score" field a normal sort should be descending while a reverse sort should be ascending
        sortDirection = getSortDirection(sortField, sortDirection);
                
		String queryJsEscaped = StringEscapeUtils.escapeJavaScript(query);
		model.addAttribute("query", query.trim());
		model.addAttribute("queryJsEscaped", queryJsEscaped.replaceAll("[ ]{2,}", " "));
		model.addAttribute("title", StringEscapeUtils.escapeJavaScript(title));
				
		logger.debug("Initial query = " + query);

        SearchResultsDTO<SearchDTO> searchResults = null;

        //shortcut for searches with an LSID
        if(query !=null && query.startsWith("urn:")){
            //format the LSID
            StringBuffer formattedQuery = new StringBuffer();
            String[] bits = StringUtils.split(query, ":", 2);
            formattedQuery.append(ClientUtils.escapeQueryChars(bits[0]));
            formattedQuery.append("\\:");
            formattedQuery.append(ClientUtils.escapeQueryChars(bits[1]));
            searchResults = searchDao.doFullTextSearch(formattedQuery.toString(), filterQuery, (String[]) null, startIndex, pageSize, sortField, sortDirection);

            
            searchResults.setResults(removedDuplicateCommonName(searchResults.getResults()));    		
            repoUrlUtils.fixRepoUrls(searchResults);
            //get occurrence count by biocache ws
            searchResults.setResults(populateOccurrenceCount(searchResults.getResults()));
            
            model.addAttribute("searchResults", searchResults);
            model.addAttribute("totalRecords", searchResults.getTotalRecords());
            model.addAttribute("lastPage", calculateLastPage(searchResults.getTotalRecords(), pageSize));
            logger.debug("Selected view: " + SEARCH_LIST);
            return SEARCH_LIST;
        }
        logger.debug("Query : " + query + " : " + ClientUtils.escapeQueryChars(query));
        query = bieEscapeQueryChars(query);
        // if filter query is null only (empty is consequence search)
        // then it is init search, do extra process as below...        
        if (filterQuery == null) {
        	List<SearchDTO> result = null;
        	boolean foundExact = false;

        	// exact search australian only
            filterQuery = new String[]{};
        	searchResults = searchDao.doExactTextSearch(query, filterQuery, startIndex, pageSize, sortField, sortDirection);

        	if(searchResults.getResults() != null && !searchResults.getResults().isEmpty()){
        		foundExact = true;
    		}

        	if(foundExact){
        	    searchResults = searchDao.doFullTextSearch(query, filterQuery, facets, startIndex, pageSize, sortField, sortDirection);
        	} else {
	        	  searchResults = searchDao.doFullTextSearch(query, filterQuery, facets, startIndex, pageSize, sortField, sortDirection);
        	}
        }
		
		// if searchResults is null then it is consequence search request.
		if(searchResults == null){
		    searchResults = searchDao.doFullTextSearch(query, filterQuery, (String[]) null, startIndex, pageSize, sortField, sortDirection);
		}		
		searchResults.setResults(removedDuplicateCommonName(searchResults.getResults()));
		
        repoUrlUtils.fixRepoUrls(searchResults);

        //get occurrence count by biocache ws
        searchResults.setResults(populateOccurrenceCount(searchResults.getResults()));
         
        //if fq contains uid then translate uid to name
        Map<String, String>collectionsMap = new HashMap<String, String>();
        try{
	        for(String fqr:filterQuery){
	        	if(fqr.contains("uid:")){
	        		String[] fqBits = StringUtils.split(fqr, ":", 2);
	        		if(fqBits != null && fqBits.length > 1 && !"".equals(fqBits[1])){
	        			String uid = fqBits[1];
	        			String jsonString = "[]";
	        			if(uid.startsWith("dr")){
	        				jsonString = PageUtils.getUrlContentAsJsonString("http://collections.ala.org.au/ws/dataResource/" + uid);
	        			}
	        			else if(uid.startsWith("dp")){
	        				jsonString = PageUtils.getUrlContentAsJsonString("http://collections.ala.org.au/ws/dataProvider/" + uid);
	        			}
	        			else if(uid.startsWith("in")){
	        				jsonString = PageUtils.getUrlContentAsJsonString("http://collections.ala.org.au/ws/institution/" + uid);
	        			}
	        			else if(uid.startsWith("co")){
	        				jsonString = PageUtils.getUrlContentAsJsonString("http://collections.ala.org.au/ws/collection/" + uid);
	        			}
	        			else if(uid.startsWith("dh")){
	        				jsonString = PageUtils.getUrlContentAsJsonString("http://collections.ala.org.au/ws/dataHub/" + uid);
	        			}
	        			ObjectMapper om = new ObjectMapper();
	        	        Map map = om.readValue(jsonString, Map.class);        	        
	        	        if(map != null && map.get("name") != null){
	        	        	Object name = map.get("name");
	        	        	collectionsMap.put(uid, name.toString());
	        	        } 
	        	        if(map != null && map.get("resourceType") != null){
	        	        	Object resourceType = map.get("resourceType");
	        	        	collectionsMap.put(uid + "_resourceType", resourceType.toString());
	        	        }
	        		}        		
	        	}
	        }
        } catch(Exception ee){
        	logger.error(ee.getMessage(), ee);
        }
        model.addAttribute("collectionsMap", collectionsMap);
        model.addAttribute("facetMap", addFacetMap(filterQuery));
        
		//get facets - and counts to model for each idx type
		Collection<FacetResultDTO> facetResults = searchResults.getFacetResults();
		Iterator<FacetResultDTO> facetIter = facetResults.iterator();
		while(facetIter.hasNext()){
			FacetResultDTO facetResultDTO = facetIter.next();
			if("idxtype".equals(facetResultDTO.getFieldName())){
				List<FieldResultDTO> fieldResults = facetResultDTO.getFieldResult();
				for(FieldResultDTO fieldResult: fieldResults){
					model.addAttribute(fieldResult.getLabel(), fieldResult.getCount());
				}
			}
		}
        
        model.addAttribute("searchResults", searchResults);
        model.addAttribute("totalRecords", searchResults.getTotalRecords());
        model.addAttribute("lastPage", calculateLastPage(searchResults.getTotalRecords(), pageSize));

        logger.debug("Selected view: " + SEARCH_LIST);
        
		return SEARCH_LIST;
	}

	private List<SearchDTO> populateOccurrenceCount(List<SearchDTO> results){
        List<String> guids = new ArrayList<String>();
        for(SearchDTO o : results){
        	if(o instanceof SearchTaxonConceptDTO){
        		SearchTaxonConceptDTO dto = (SearchTaxonConceptDTO)o;
        		guids.add(dto.getGuid());
        	}
        }
        if(guids.size() > 0){
        	try{
		        List<NameValuePair> nameValuePairs = new ArrayList<NameValuePair>();
		        nameValuePairs.add(new NameValuePair("separator", ","));
		        nameValuePairs.add(new NameValuePair("guids", org.apache.commons.lang.StringUtils.join(guids, ',')));
		        String json = PageUtils.getUrlContentAsJsonStringByPost(biocacheServiceUrl +"/occurrences/taxaCount", nameValuePairs.toArray(new NameValuePair[]{}));
		        logger.debug("JSON : " +json);
	        	Map map = mapper.readValue(json, Map.class);	        
		        for(SearchDTO o : results){
		        	if(o instanceof SearchTaxonConceptDTO){
		        		SearchTaxonConceptDTO dto = (SearchTaxonConceptDTO)o;
		        		Integer ctr = (Integer) map.get(dto.getGuid());
		        		dto.setOccCount(ctr);        		
		        	}
		        }
	        }
	        catch(Exception e){
	        	//do nothing
	        	logger.error("*** invalid json string: " + e);
	        }
        }
		return results;
	}
	
	private List<SearchDTO> removedDuplicateCommonName(List<SearchDTO> results){
		for(SearchDTO result : results){
			if(result instanceof SearchTaxonConceptDTO){
				String names = ((SearchTaxonConceptDTO)result).getCommonName();			
				if(names != null){
					List<String> commonNames = org.springframework.util.CollectionUtils.arrayToList(names.split(","));
		            Hashtable<String, String> hlnames = new Hashtable<String, String>();
		            for(String name : commonNames){
		            	if(!hlnames.containsKey(name.trim().toLowerCase())){
		            		hlnames.put(name.trim().toLowerCase(), name);
		            	}
		            }
		            if(!hlnames.isEmpty()){
		            	List<String> lnames = new ArrayList<String>(hlnames.values());
		            	Collections.sort(lnames);
		            	names = lnames.toString();
		            	if(names != null && names.length() > 1){
		            		((SearchTaxonConceptDTO)result).setCommonName(names.substring(1, names.length() - 1));
		            	}
		            	else{
		            		((SearchTaxonConceptDTO)result).setCommonName(names);
		            	}
		            }   
				}
			}
		}
		return results;
	}
	
	@RequestMapping(value = {"/search.json","/search.xml","/ws/search.json","/ws/search.xml"}, method = RequestMethod.GET)
	public ModelAndView  searchJsonXml(
			@RequestParam(value="q", required=false) String query,
			@RequestParam(value="fq", required=false) String[] filterQuery,
			@RequestParam(value="start", required=false, defaultValue="0") Integer startIndex,
			@RequestParam(value="pageSize", required=false, defaultValue ="10") Integer pageSize,
			@RequestParam(value="sort", required=false, defaultValue="score") String sortField,
			@RequestParam(value="dir", required=false, defaultValue ="asc") String sortDirection,
			@RequestParam(value="title", required=false, defaultValue ="Search Results") String title,
            @RequestParam(value="facet", required=false) String[] facets,
		    Model model,
            HttpServletRequest request) throws Exception {
		
		if (startIndex == null) {
            startIndex = 0;
        }
        if (pageSize == null) {
            pageSize = 20;
        }
        if (sortField.isEmpty()) {
            sortField = "score";
        }
        if (sortDirection.isEmpty()) {
            sortDirection = "asc";
        }
        sortDirection = getSortDirection(sortField, sortDirection);
        logger.debug("Starting to search...");
        //shortcut for searches with an LSID
        SearchResultsDTO<SearchDTO> searchResults = null;
        if(query !=null && query.startsWith("urn:")){
            //format the LSID
            StringBuffer formattedQuery = new StringBuffer();
            String[] bits = StringUtils.split(query, ":", 2);
            formattedQuery.append(ClientUtils.escapeQueryChars(bits[0]));
            formattedQuery.append("\\:");
            formattedQuery.append(ClientUtils.escapeQueryChars(bits[1]));
            searchResults = searchDao.doFullTextSearch(formattedQuery.toString(), filterQuery, facets, startIndex, pageSize, sortField, sortDirection);

        } else {
            query = bieEscapeQueryChars(query);
            searchResults = searchDao.doFullTextSearch(query, filterQuery, facets, startIndex, pageSize, sortField, sortDirection);
        }
        logger.debug("Finished the search...");
        //get occurrence count by biocache ws
        searchResults.setResults(populateOccurrenceCount(searchResults.getResults()));
        logger.debug("Finished setting the results...");
        return new ModelAndView(SEARCH_LIST, "searchResults", searchResults);
	}
    /**
     * Provides the auto complete service. 
     *
     * @param query The value to auto complete
     * @param geoRefOnly When true only include results that have some geospatial occurrence records
     * @param idxType The index type to limit see bie-hbase/src/main/java/org/ala/dao/IndexedTypes
     * @param maxTerms The maximum number of results to return
     * @param model
     * @throws Exception
     */
    @RequestMapping(value={"/search/auto.json*","/ws/search/auto.json*"}, method = RequestMethod.GET)
    public String searchForAutocompleteValues(
            @RequestParam(value="q", required=true) String query,
            @RequestParam(value="fq", required=false) String[] filterQuery,
            @RequestParam(value="geoOnly", required=false) boolean geoRefOnly,
            @RequestParam(value="idxType", required=false) String idxType,
            @RequestParam(value="limit", required=false, defaultValue ="10") int maxTerms,
            Model model) throws Exception {

        
        IndexedTypes it = idxType != null ? IndexedTypes.valueOf(idxType.toUpperCase()):null;
        List<AutoCompleteDTO> autoCompleteList = searchDao.getAutoCompleteList(query,filterQuery,it, geoRefOnly , maxTerms);
        model.addAttribute("autoCompleteList", autoCompleteList);
        logger.debug("Autocomplete on " + query + " geoOnly: " + geoRefOnly + ", return size: " + autoCompleteList.size() );
        return AUTO_JSON;
    }

    /**
     * Changes the direction of the sore if the sortField is score
     * @param sortField
     * @param sortDirection
     * @return
     */
    private String getSortDirection(String sortField, String sortDirection){
        String direction = sortDirection;
        if(sortField.equals("score")){
            if(sortDirection.equals("asc"))
                direction = "desc";
            else
                direction = "asc";
        }
        return direction;
    }
	
    /**
     * Create a HashMap for the filter queries
     *
     * @param filterQuery
     * @return
     */
    private HashMap<String, String> addFacetMap(String[] filterQuery) {
               HashMap<String, String> facetMap = new HashMap<String, String>();

        if (filterQuery != null && filterQuery.length > 0) {
            logger.debug("filterQuery = "+StringUtils.join(filterQuery, "|"));
            for (String fq : filterQuery) {
                if (fq != null && !fq.isEmpty()) {
                    String[] fqBits = StringUtils.split(fq, ":", 2);
                    facetMap.put(fqBits[0], fqBits[1]);
                }
            }
        }
        return facetMap;
    }

     /**
     * Calculate the last page number for pagination
     * 
     * @param totalRecords
     * @param pageSize
     * @return
     */
    private Integer calculateLastPage(Long totalRecords, Integer pageSize) {
        Integer lastPage = 0;
        Integer lastRecordNum = totalRecords.intValue();
        
        if (pageSize > 0) {
            lastPage = (lastRecordNum / pageSize) + ((lastRecordNum % pageSize > 0) ? 1 : 0);
        }
        
        return lastPage;
    }
	
	/**
	 * @param searchDao the searchDao to set
	 */
	public void setSearchDao(FulltextSearchDao searchDao) {
		this.searchDao = searchDao;
	}

	/**
	 * @param repoUrlUtils the repoUrlUtils to set
	 */
	public void setRepoUrlUtils(RepoUrlUtils repoUrlUtils) {
		this.repoUrlUtils = repoUrlUtils;
	}

    /**
     * @return the biocacheServiceUrl
     */
    public String getBiocacheServiceUrl() {
        return biocacheServiceUrl;
    }

    /**
     * @param biocacheServiceUrl the biocacheServiceUrl to set
     */
    public void setBiocacheServiceUrl(String biocacheServiceUrl) {
        this.biocacheServiceUrl = biocacheServiceUrl;
    }

    /**
     * @return the defaultDownloadFields
     */
    public String getDefaultDownloadFields() {
        return defaultDownloadFields;
    }

    /**
     * @param defaultDownloadFields the defaultDownloadFields to set
     */
    public void setDefaultDownloadFields(String defaultDownloadFields) {
        this.defaultDownloadFields = defaultDownloadFields;
    }
}
