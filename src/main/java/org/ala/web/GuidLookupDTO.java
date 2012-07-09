package org.ala.web;

public class GuidLookupDTO {

    private String infoSourceName;
    private String infoSourceURL;
    private String infoSourceId;
    private String identifier;
    private String name;
    private String acceptedIdentifier;
    private String acceptedName;
    private GuidLookupDTO[] otherGuids;
    
	/**
	 * @return the infoSourceName
	 */
	public String getInfoSourceName() {
		return infoSourceName;
	}
	/**
	 * @param infoSourceName the infoSourceName to set
	 */
	public void setInfoSourceName(String infoSourceName) {
		this.infoSourceName = infoSourceName;
	}
	/**
	 * @return the infoSourceURL
	 */
	public String getInfoSourceURL() {
		return infoSourceURL;
	}
	/**
	 * @param infoSourceURL the infoSourceURL to set
	 */
	public void setInfoSourceURL(String infoSourceURL) {
		this.infoSourceURL = infoSourceURL;
	}
	/**
	 * @return the identifier
	 */
	public String getIdentifier() {
		return identifier;
	}
	/**
	 * @param identifier the identifier to set
	 */
	public void setIdentifier(String identifier) {
		this.identifier = identifier;
	}
	/**
	 * @return the infoSourceId
	 */
	public String getInfoSourceId() {
		return infoSourceId;
	}
	/**
	 * @param infoSourceId the infoSourceId to set
	 */
	public void setInfoSourceId(String infoSourceId) {
		this.infoSourceId = infoSourceId;
	}
	/**
	 * @return the name
	 */
	public String getName() {
		return name;
	}
	/**
	 * @param name the name to set
	 */
	public void setName(String name) {
		this.name = name;
	}
  /**
   * @return the acceptedIdentifier
   */
  public String getAcceptedIdentifier() {
    return acceptedIdentifier;
  }
  /**
   * @param acceptedGuid the acceptedIdentifier to set
   */
  public void setAcceptedIdentifier(String acceptedGuid) {
    this.acceptedIdentifier = acceptedGuid;
  }
  /**
   * @return the acceptedName
   */
  public String getAcceptedName() {
    return acceptedName;
  }
  /**
   * @param acceptedName the acceptedName to set
   */
  public void setAcceptedName(String acceptedName) {
    this.acceptedName = acceptedName;
  }
  /**
   * @return the otherGuids
   */
  public GuidLookupDTO[] getOtherGuids() {
    return otherGuids;
  }
  /**
   * @param others the others to set
   */
  public void setOtherGuids(GuidLookupDTO[] otherGuids) {
    this.otherGuids = otherGuids;
  }

}
