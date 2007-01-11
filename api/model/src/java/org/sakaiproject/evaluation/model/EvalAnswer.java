package org.sakaiproject.evaluation.model;

// Generated Jan 11, 2007 4:37:19 PM by Hibernate Tools 3.2.0.beta6a

import java.util.Date;

/**
 * EvalAnswer generated by hbm2java
 */
public class EvalAnswer implements java.io.Serializable {

	// Fields    

	private Long id;

	private Date lastModified;

	private EvalItem item;

	private EvalResponse response;

	private String text;

	private Integer numeric;

	// Constructors

	/** default constructor */
	public EvalAnswer() {
	}

	/** minimal constructor */
	public EvalAnswer(Date lastModified, EvalItem item, EvalResponse response) {
		this.lastModified = lastModified;
		this.item = item;
		this.response = response;
	}

	/** full constructor */
	public EvalAnswer(Date lastModified, EvalItem item, EvalResponse response, String text, Integer numeric) {
		this.lastModified = lastModified;
		this.item = item;
		this.response = response;
		this.text = text;
		this.numeric = numeric;
	}

	// Property accessors
	public Long getId() {
		return this.id;
	}

	public void setId(Long id) {
		this.id = id;
	}

	public Date getLastModified() {
		return this.lastModified;
	}

	public void setLastModified(Date lastModified) {
		this.lastModified = lastModified;
	}

	public EvalItem getItem() {
		return this.item;
	}

	public void setItem(EvalItem item) {
		this.item = item;
	}

	public EvalResponse getResponse() {
		return this.response;
	}

	public void setResponse(EvalResponse response) {
		this.response = response;
	}

	public String getText() {
		return this.text;
	}

	public void setText(String text) {
		this.text = text;
	}

	public Integer getNumeric() {
		return this.numeric;
	}

	public void setNumeric(Integer numeric) {
		this.numeric = numeric;
	}

}
