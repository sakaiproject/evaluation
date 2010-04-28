/**
 * $Id$
 * $URL$
 * EvalEmailsLogicImpl.java - evaluation - Dec 29, 2006 10:07:31 AM - azeckoski
 **************************************************************************
 * Copyright (c) 2008 Centre for Applied Research in Educational Technologies, University of Cambridge
 * Licensed under the Educational Community License version 1.0
 * 
 * A copy of the Educational Community License has been included in this 
 * distribution and is available at: http://www.opensource.org/licenses/ecl1.php
 *
 * Aaron Zeckoski (azeckoski@gmail.com) (aaronz@vt.edu) (aaron@caret.cam.ac.uk)
 */

package org.sakaiproject.evaluation.logic;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.sakaiproject.evaluation.constant.EvalConstants;
import org.sakaiproject.evaluation.dao.EvaluationDao;
import org.sakaiproject.evaluation.logic.externals.EvalExternalLogic;
import org.sakaiproject.evaluation.logic.model.EvalGroup;
import org.sakaiproject.evaluation.logic.model.EvalUser;
import org.sakaiproject.evaluation.model.EvalAssignGroup;
import org.sakaiproject.evaluation.model.EvalEmailTemplate;
import org.sakaiproject.evaluation.model.EvalEvaluation;
import org.sakaiproject.evaluation.model.EvalLock;
import org.sakaiproject.evaluation.model.EvalQueuedEmail;
import org.sakaiproject.evaluation.utils.SettingsLogicUtils;
import org.sakaiproject.evaluation.utils.EvalUtils;
import org.sakaiproject.evaluation.utils.TextTemplateLogicUtils;
import org.sakaiproject.taskstream.client.TaskStatusStandardValues;

/**
 * EvalEmailsLogic implementation,
 * this is a BACKUP service and should only depend on LOWER and BOTTOM services
 * (and maybe other BACKUP services if necessary)
 *
 * @author Aaron Zeckoski (aaronz@vt.edu)
 */
public class EvalEmailsLogicImpl implements EvalEmailsLogic {

   private static Log log = LogFactory.getLog(EvalEmailsLogicImpl.class);
   private static Log metric = LogFactory.getLog("metrics." + EvalEmailsLogicImpl.class.getName());

   // Event names cannot be over 32 chars long              // max-32:12345678901234567890123456789012
   protected final String EVENT_EMAIL_CREATED =                      "eval.email.eval.created";
   protected final String EVENT_EMAIL_AVAILABLE =                    "eval.email.eval.available";
   protected final String EVENT_EMAIL_GROUP_AVAILABLE =              "eval.email.evalgroup.available";
   protected final String EVENT_EMAIL_REMINDER =                     "eval.email.eval.reminders";
   protected final String EVENT_EMAIL_RESULTS =                      "eval.email.eval.results";
   
   //SAK-6320 ORA-01795: maximum number of expressions in a list is 1000
   //TODO generalize through dao
   protected final String QUERY_LIMIT = "eval.query.limit";
   protected final String DEFAULT_QUERY_LIMIT = "100";
   protected final int ORACLE_MAX_CLAUSES_IN_QUERY = 999;

   private EvalCommonLogic commonLogic;
   public void setCommonLogic(EvalCommonLogic commonLogic) {
      this.commonLogic = commonLogic;
   }
   
   private EvaluationDao dao;
   public void setDao(EvaluationDao dao) {
	   this.dao = dao;
   }
   
   private EvalSettings settings;
   public void setSettings(EvalSettings settings) {
      this.settings = settings;
   }

   private EvalEvaluationService evaluationService;
   public void setEvaluationService(EvalEvaluationService evaluationService) {
      this.evaluationService = evaluationService;
   }
   
   private EvalExternalLogic externalLogic;
   public void setExternalLogic(EvalExternalLogic externalLogic) {
	      this.externalLogic = externalLogic;
   }

   // INIT method
   public void init() {
      log.debug("Init");
      String serverId = commonLogic.getConfigurationSetting(EvalExternalLogic.SETTING_SERVER_ID, "UNKNOWN_SERVER_ID");
     	if(log.isInfoEnabled())
    		log.info("EvalEmailLogicImpl.init() on " + serverId);
      //clear email locks on startup
      List<EvalLock> locks = dao.obtainLocksForHolder(EvalConstants.EMAIL_LOCK_PREFIX, serverId);
   	if(log.isInfoEnabled())
		log.info("EvalEmailLogicImpl.init() serverId '" + serverId + "' held " + locks.size() + " locks at startup. ");
      if(locks != null && !locks.isEmpty()) {
    	  for(EvalLock lock:locks) {
    		  dao.releaseLock(lock.getName(), externalLogic.getCurrentUserId());
    		   	if(log.isInfoEnabled())
    				log.info("EvalEmailLogicImpl.init() Lock '" + lock.getName() + "' held by '" + lock.getHolder() + " was released. ");
    	  }
      }
      //make sure we have the settings
      if(((Integer)settings.get(settings.EMAIL_SEND_QUEUED_REPEAT_INTERVAL)) != null &&
    		  ((Integer)settings.get(settings.EMAIL_SEND_QUEUED_START_INTERVAL)) != null) {
    	  //and settings aren't 0
    	  if((((Integer)settings.get(settings.EMAIL_SEND_QUEUED_REPEAT_INTERVAL)).intValue() != 0) &&
    	  	((Integer)settings.get(settings.EMAIL_SEND_QUEUED_START_INTERVAL)).intValue() != 0) {
    		  //and window for sending is active
    		  if(((Boolean)settings.get(EvalSettings.EMAIL_SEND_QUEUED_ENABLED)) != null) {
       			  if(log.isInfoEnabled())
        			  log.info("EvalEmailLogicImpl.init() initiate polling for queued email. ");
    		  
	    		  // polling for queued email
	    		  initiateSendEmailTimer();
    		  }
    		  else {
    			  if(log.isInfoEnabled())
        			  log.info("EvalEmailLogicImpl.init() polling for queued email is disabled. ");
    		  }
    	  }
    	  else {
    		  if(log.isInfoEnabled())
    			  log.info("EvalEmailLogicImpl.init() polling repeat or start interval is 0 (never)" +
    			  		" so polling for queued email is disabled. ");
    	  }
      }
      else {
			  if(log.isInfoEnabled())
    			  log.info("EvalEmailLogicImpl.init() polling for queued email settings are null. ");
      }
   }

   /**
    * Send email from a holding table EVAL_QUEUED_EMAIL using locks to create non-overlapping
    * sets of emails (to avoid servers sending duplicates of email in the holding table).
    */
   protected void initiateSendEmailTimer() {
	   //hold lock for 48 hr so it isn't taken by another server before being released
	   final long holdLock = 1000 * 60 * 60 * 48;

	   // check the holding table every "n" minutes
	   final long repeatInterval = 1000 * 60 * ((Integer)settings.get(settings.EMAIL_SEND_QUEUED_REPEAT_INTERVAL)).intValue();
	   // first run after "n" minutes and a random delay
	   long startDelay =  (1000 * 60 * ((Integer)settings.get(settings.EMAIL_SEND_QUEUED_START_INTERVAL)).intValue())
	   		+ (1000 * 60 * new Random().nextInt(10));

	   
      TimerTask runSendEmailTask = new TimerTask() {
         @SuppressWarnings("unchecked")
         @Override
         public void run() {
        	 
        	 String serverId = commonLogic.getConfigurationSetting(EvalExternalLogic.SETTING_SERVER_ID, "UNKNOWN_SERVER_ID");
        	 // setting of 0 (never)
        	 if(((Integer)settings.get(settings.EMAIL_SEND_QUEUED_REPEAT_INTERVAL)).intValue() == 0 ) {
        		 if(log.isInfoEnabled())
        			 log.info(this + ".initiateSendEmailTimer(): EMAIL_SEND_QUEUED_REPEAT_INTERVAL = 0: quit. "); 
        		 return;
        	 }
        	 if(((Integer)settings.get(settings.EMAIL_SEND_QUEUED_START_INTERVAL)).intValue() == 0 ) {
        		 if(log.isInfoEnabled())
        			 log.info(this + ".initiateSendEmailTimer():  EMAIL_SEND_QUEUED_START_INTERVAL = 0: quit. "); 
        		 return;
        	 }
        	 
        	 String from = (String) settings.get(EvalSettings.FROM_EMAIL_ADDRESS);
        	 String deliveryOption = (String) settings.get(EvalSettings.EMAIL_DELIVERY_OPTION);
        	 Boolean logEmailRecipients = (Boolean) settings.get(EvalSettings.LOG_EMAIL_RECIPIENTS);
        	 Integer batch = (Integer) settings.get(EvalSettings.EMAIL_BATCH_SIZE);
        	 Integer wait = (Integer) settings.get(EvalSettings.EMAIL_WAIT_INTERVAL);
        	 Integer every = (Integer) settings.get(EvalSettings.LOG_PROGRESS_EVERY);
        	 
        	 int numProcessed = 0;
        	 
      		 if(log.isDebugEnabled())
    			 log.debug(serverId + " is beginning runSendEmailTask.run() ");
        	 
        	 //is the enabling flag true?
        	 if(!(((Boolean)settings.get(EvalSettings.EMAIL_SEND_QUEUED_ENABLED))).booleanValue()) {
        		 if(log.isDebugEnabled())
        			 log.debug(serverId + " runSendEmailTask.run(): EMAIL_SEND_QUEUED_ENABLED is false so quitting. "); 
        		 return;
        	 }
        	 
        	//is there something to do?
        	 if (deliveryOption.equals(EvalConstants.EMAIL_DELIVERY_NONE)
        	 	&& !logEmailRecipients.booleanValue()) {
        	 if (log.isDebugEnabled())
        	 	log.debug(serverId + " runSendEmailTask.run(): EMAIL_DELIVERY_NONE and no logging of email recipients. " +
        	 			" There is no work to do so quitting.");
        	 	return;
        	 }
        	 if(log.isDebugEnabled())
        		log.debug(serverId + " runSendEmailTask.run(): checking if server holds a lock. ");
        	
        	//have I got an email_lock? if so, quit (one run() per server at a time)
        	List<EvalLock> locks = dao.obtainLocksForHolder(EvalConstants.EMAIL_LOCK_PREFIX, serverId);
        	if(locks != null && !locks.isEmpty()) {
              	if(log.isDebugEnabled()) {
            		log.debug(serverId + " runSendEmailTask.run(): server has a lock: quitting. ");
            		for(EvalLock lock:locks) {
            			log.debug(serverId + " has lock: " + lock.getName() + ".");
            		}
              	}
              	return;
        	}
           	if(log.isDebugEnabled())
        		log.debug(serverId +" runSendEmailTask.run(): server has no locks: continuing. ");
           	
           	List<Long> emailIds = new ArrayList<Long>();
           	EvalQueuedEmail email = null;
           	
           	//try these locks
           	List<String> lockNames = dao.getQueuedEmailLocks();
          	if(log.isDebugEnabled())
        		log.debug(serverId + " runSendEmailTask.run(): server found " + lockNames.size() + " lock names" +
        				" in the queued notification table. ");
           	for(String lockName: lockNames) {
            	if(log.isDebugEnabled())
            		log.debug(serverId + " runSendEmailTask.run(): server is trying to obtain lock " + lockName + ". ");
             	/*
               	 * Note: Another server may acquire the lock if the repeatInterval is shorter than the TimerTask's processing time. 
               	 * 		 This would defeat the purpose of a server holding a lock while processing a batch of email. We set the 
               	 * 		 repeatInterval to a very high value and refresh the lock by obtaining it periodically until the TimerTask
               	 * 		 processing has finished as a precaution against the server losing its lock before finishing.
               	 * 		 TODO: CT-798 TQ: server id as holder of lock prevents re-obtaining lock after restart
               	 */
           		Boolean lockObtained = dao.obtainLock(lockName, serverId, holdLock);
                // only execute the code if we have an exclusive lock
                if (lockObtained != null && lockObtained) {
                	//process notifications for this lock and then quit
                   	if(log.isDebugEnabled())
                		log.debug(serverId + " runSendEmailTask.run(): server obtained lock " + lockName + ". ");
                   	emailIds.clear();
                	//claim the emails associated with this lock
                   	emailIds = dao.getQueuedEmailByLockName(lockName);
                   	//metric logging
                  	if(metric.isInfoEnabled())
                		metric.info(serverId + " metric runSendEmailTask.run(): server claimed " + emailIds.size() + " queued notifications. ");
                  	boolean deferExceptions = true;
                  	for(Long id: emailIds) {
                  		email = dao.findById(EvalQueuedEmail.class, id);
                     	if(log.isDebugEnabled())
                    		log.debug(serverId + " runSendEmailTask.run(): retrieved queued notification " + email.toString() + ". ");
                     	//TODO: see CT-719 for an array version of send, which should be faster
                     	//send one email and delete from holding table for loss-less restart behavior
                     	String[] to = new String[]{email.getToAddress()};
                  		commonLogic.sendEmailsToAddresses(from, to, email.getSubject(), email.getMessage(), deferExceptions);
                  		if (deliveryOption.equals(EvalConstants.EMAIL_DELIVERY_LOG)) {
                  			//log email
                		   log.info(serverId + " runSendEmailTask.run() sent notification: " + email.toString());
                  		}
                  		dao.delete(email);
                  		if(log.isDebugEnabled())
                		   log.debug(serverId + " runSendEmailTask.run(): server deleted notification from the email holding table: " +
                		   		email.toString() + ". ");
                  		
                  		// handle throttling email delivery and logging progress
                  		numProcessed = logEmailsProcessed(batch, wait, every, numProcessed, "sent");
                  	}
                  	dao.releaseLock(lockName, serverId);
                  	if(log.isDebugEnabled())
                		log.debug(serverId + " runSendEmailTask.run(): server released lock " + lockName + ". ");
                  	break;
                }
                else {
                  	if(log.isDebugEnabled())
                		log.debug(serverId + " runSendEmailTask.run(): server didn't obtained lock "
                				+ lockName + ". Try to obtain the next lock. ");
                }
           	}
          	if(log.isDebugEnabled())
        		log.debug(serverId + " runSendEmailTask.run(): done sending any notifications. ");
         }
      };

      // now we need to obtain a lock and then run the task if we have it
      Timer timer = new Timer(true);
      if(log.isDebugEnabled()) {
    	  log.debug("Initializing checking for queued email, first run in " + (startDelay/1000) + " seconds " +
      		"and subsequent runs will happen every " + (repeatInterval/1000) + " seconds after that. ");
      }
      timer.schedule(runSendEmailTask, startDelay, repeatInterval);
   }


   /* (non-Javadoc)
    * @see org.sakaiproject.evaluation.logic.EvalEmailsLogic#sendEvalCreatedNotifications(java.lang.Long, boolean)
    */
   public String[] sendEvalCreatedNotifications(Long evaluationId, boolean includeOwner) {
      log.debug("evaluationId: " + evaluationId + ", includeOwner: " + includeOwner);

      EvalEvaluation eval = getEvaluationOrFail(evaluationId);
      String from = getFromEmailOrFail(eval);
      EvalEmailTemplate emailTemplate = getEmailTemplateOrFail(EvalConstants.EMAIL_TEMPLATE_CREATED, evaluationId);

      Map<String, String> replacementValues = new HashMap<String, String>();
      replacementValues.put("HelpdeskEmail", from);

      // setup the opt-in, opt-out, and add questions variables
      int addItems = ((Integer) settings.get(EvalSettings.ADMIN_ADD_ITEMS_NUMBER)).intValue();
      if (! eval.getInstructorOpt().equals(EvalConstants.INSTRUCTOR_REQUIRED) || (addItems > 0)) {
         if (eval.getInstructorOpt().equals(EvalConstants.INSTRUCTOR_OPT_IN)) {
            // if eval is opt-in notify instructors that they may opt in
            replacementValues.put("ShowOptInText", "true");
         } else if (eval.getInstructorOpt().equals(EvalConstants.INSTRUCTOR_OPT_OUT)) {
            // if eval is opt-out notify instructors that they may opt out
            replacementValues.put("ShowOptOutText", "true");
         }
         if (addItems > 0) {
            // if eval allows instructors to add questions notify instructors they may add questions
            replacementValues.put("ShowAddItemsText", "true");
         }
      }

      String message = emailTemplate.getMessage();

      // get the associated groups for this evaluation
      Map<Long, List<EvalGroup>> evalGroups = evaluationService.getEvalGroupsForEval(new Long[] { evaluationId }, true, null);

      // only one possible map key so we can assume evaluationId
      List<EvalGroup> groups = evalGroups.get(evaluationId);
      if (log.isDebugEnabled()) {
         log.debug("Found " + groups.size() + " groups for new evaluation: " + evaluationId);
      }

      List<String> sentEmails = new ArrayList<String>();
      // loop through contexts and send emails to correct users in each evalGroupId
      for (int i = 0; i < groups.size(); i++) {
         EvalGroup group = (EvalGroup) groups.get(i);
         if (EvalConstants.GROUP_TYPE_INVALID.equals(group.type)) {
            continue; // skip processing for invalid groups
         }

         Set<String> userIdsSet = commonLogic.getUserIdsForEvalGroup(group.evalGroupId,
               EvalConstants.PERM_BE_EVALUATED);
         // add in the owner or remove them based on the setting
         if (includeOwner) {
            userIdsSet.add(eval.getOwner());
         } else {
            if (userIdsSet.contains(eval.getOwner())) {
               userIdsSet.remove(eval.getOwner());
            }
         }

         // skip ahead if there is no one to send to
         if (userIdsSet.size() == 0) continue;

         // turn the set into an array
         String[] toUserIds = (String[]) userIdsSet.toArray(new String[] {});
         if (log.isDebugEnabled()) {
            log.debug("Found " + toUserIds.length + " users (" + toUserIds + ") to send "
                  + EvalConstants.EMAIL_TEMPLATE_CREATED + " notification to for new evaluation ("
                  + evaluationId + ") and evalGroupId (" + group.evalGroupId + ")");
         }

         // replace the text of the template with real values
         message = makeEmailMessage(message, eval, group, replacementValues);
         String subject = makeEmailMessage(emailTemplate.getSubject(), eval, group, replacementValues);

         // send the actual emails for this evalGroupId
         String[] emailAddresses = commonLogic.sendEmailsToUsers(from, toUserIds, subject, message, true);
         log.info("Sent evaluation created message to " + emailAddresses.length + " users (attempted to send to "+toUserIds.length+")");
         // store sent emails to return
         for (int j = 0; j < emailAddresses.length; j++) {
            sentEmails.add(emailAddresses[j]);            
         }
         commonLogic.registerEntityEvent(EVENT_EMAIL_CREATED, eval);
      }

      return (String[]) sentEmails.toArray(new String[] {});
   }


   /* (non-Javadoc)
    * @see org.sakaiproject.evaluation.logic.EvalEmailsLogic#sendEvalAvailableNotifications(java.lang.Long, boolean)
    */
   public String[] sendEvalAvailableNotifications(Long evaluationId, boolean includeEvaluatees) {
      log.debug("evaluationId: " + evaluationId + ", includeEvaluatees: " + includeEvaluatees);

      Set<String> userIdsSet = null;
      String message = null;
      boolean studentNotification = true;

      EvalEvaluation eval = getEvaluationOrFail(evaluationId);
      String from = getFromEmailOrFail(eval);
      EvalEmailTemplate emailTemplate = getEmailTemplateOrFail(EvalConstants.EMAIL_TEMPLATE_AVAILABLE, evaluationId);
      // get the instructor opt-in email template
      EvalEmailTemplate emailOptInTemplate = getEmailTemplateOrFail(EvalConstants.EMAIL_TEMPLATE_AVAILABLE_OPT_IN, null);

      // get the associated assign groups for this evaluation
      Map<Long, List<EvalAssignGroup>> evalAssignGroups = 
         evaluationService.getAssignGroupsForEvals(new Long[] { evaluationId }, true, null);
      List<EvalAssignGroup> assignGroups = evalAssignGroups.get(evaluationId);

      List<String> sentEmails = new ArrayList<String>();
      // loop through groups and send emails to correct users group
      for (int i = 0; i < assignGroups.size(); i++) {
         EvalAssignGroup assignGroup = assignGroups.get(i);
         EvalGroup group = commonLogic.makeEvalGroupObject(assignGroup.getEvalGroupId());
         if (eval.getInstructorOpt().equals(EvalConstants.INSTRUCTOR_REQUIRED)) {
            //notify students
            userIdsSet = commonLogic.getUserIdsForEvalGroup(group.evalGroupId,
                  EvalConstants.PERM_TAKE_EVALUATION);
            studentNotification = true;
         } else {
            //instructor may opt-in or opt-out
            if (assignGroup.getInstructorApproval().booleanValue()) {
               //instructor has opted-in, notify students
               userIdsSet = commonLogic.getUserIdsForEvalGroup(group.evalGroupId,
                     EvalConstants.PERM_TAKE_EVALUATION);
               studentNotification = true;
            } else {
               if (eval.getInstructorOpt().equals(EvalConstants.INSTRUCTOR_OPT_IN) && includeEvaluatees) {
                  // instructor has not opted-in, notify instructors
                  userIdsSet = commonLogic.getUserIdsForEvalGroup(group.evalGroupId,
                        EvalConstants.PERM_BE_EVALUATED);
                  studentNotification = false;
               } else {
                  userIdsSet = new HashSet<String>();
               }
            }
         }

         // skip ahead if there is no one to send to
         if (userIdsSet.size() == 0) continue;

         // turn the set into an array
         String[] toUserIds = (String[]) userIdsSet.toArray(new String[] {});

         if (log.isDebugEnabled()) {
            log.debug("Found " + toUserIds.length + " users (" + toUserIds + ") to send "
                  + EvalConstants.EMAIL_TEMPLATE_CREATED + " notification to for available evaluation ("
                  + evaluationId + ") and group (" + group.evalGroupId + ")");
         }

         // replace the text of the template with real values
         Map<String, String> replacementValues = new HashMap<String, String>();
         replacementValues.put("HelpdeskEmail", from);

         // choose from 2 templates
         EvalEmailTemplate currentTemplate = emailTemplate;
         if (! studentNotification) {
            currentTemplate = emailOptInTemplate;
         }
         message = makeEmailMessage(currentTemplate.getMessage(), eval, group, replacementValues);
         String subject = makeEmailMessage(currentTemplate.getSubject(), eval, group, replacementValues);

         // send the actual emails for this evalGroupId
         String[] emailAddresses = commonLogic.sendEmailsToUsers(from, toUserIds, subject, message, true);
         log.info("Sent evaluation available message to " + emailAddresses.length + " users (attempted to send to "+toUserIds.length+")");
         // store sent emails to return
         for (int j = 0; j < emailAddresses.length; j++) {
            sentEmails.add(emailAddresses[j]);            
         }
         commonLogic.registerEntityEvent(EVENT_EMAIL_AVAILABLE, eval);
      }

      return (String[]) sentEmails.toArray(new String[] {});
   }


   /* (non-Javadoc)
    * @see org.sakaiproject.evaluation.logic.EvalEmailsLogic#sendEvalAvailableGroupNotification(java.lang.Long, java.lang.String)
    */
   public String[] sendEvalAvailableGroupNotification(Long evaluationId, String evalGroupId) {

      List<String> sentEmails = new ArrayList<String>();

      // get group
      EvalGroup group = commonLogic.makeEvalGroupObject(evalGroupId);
      // only process valid groups
      if ( EvalConstants.GROUP_TYPE_INVALID.equals(group.type) ) {
         throw new IllegalArgumentException("Invalid group type for group with id (" + evalGroupId + "), cannot send available emails");
      }

      EvalEvaluation eval = getEvaluationOrFail(evaluationId);
      String from = getFromEmailOrFail(eval);
      EvalEmailTemplate emailTemplate = getEmailTemplateOrFail(EvalConstants.EMAIL_TEMPLATE_AVAILABLE_OPT_IN, evaluationId);

      //get student ids
      Set<String> userIdsSet = commonLogic.getUserIdsForEvalGroup(group.evalGroupId,
            EvalConstants.PERM_TAKE_EVALUATION);
      if (userIdsSet.size() > 0) {
         String[] toUserIds = (String[]) userIdsSet.toArray(new String[] {});

         // replace the text of the template with real values
         Map<String, String> replacementValues = new HashMap<String, String>();
         replacementValues.put("HelpdeskEmail", from);
         String message = makeEmailMessage(emailTemplate.getMessage(), eval, group, replacementValues);
         String subject = makeEmailMessage(emailTemplate.getSubject(), eval, group, replacementValues);

         // send the actual emails for this evalGroupId
         String[] emailAddresses = commonLogic.sendEmailsToUsers(from, toUserIds, subject, message, true);
         log.info("Sent evaluation available group message to " + emailAddresses.length + " users (attempted to send to "+toUserIds.length+")");
         // store sent emails to return
         for (int j = 0; j < emailAddresses.length; j++) {
            sentEmails.add(emailAddresses[j]);            
         }
         commonLogic.registerEntityEvent(EVENT_EMAIL_GROUP_AVAILABLE, eval);
      }

      return (String[]) sentEmails.toArray(new String[] {});
   }
   
	private String reportError(String streamUrl, String entryTag, String payload) {
		String entryUrl = null;
		if(streamUrl != null) {
			entryUrl = evaluationService.newTaskStatusEntry(streamUrl,
					entryTag, TaskStatusStandardValues.RUNNING, payload);
		}
		else {
			log.error(this + ".reportError - TaskStatusService streamUrl is null. entryTag " + entryTag + " payoad " + payload);
		}
		return entryUrl;
	}
   
   /*
	 * (non-Javadoc)
	 * 
	 * @see org.sakaiproject.evaluation.logic.EvalEmailsLogic#sendEvalAvailableSingleEmail()
	 */
	public String[] sendEvalAvailableSingleEmail(String streamUrl) {
		Long[] evalIds = new Long[] {};
		String[] toAddresses = new String[] {};
		List<EvalAssignGroup> groups = null;
		Set<String> allUserIds = new HashSet<String>();
		Set<String> userIdsTakingEval = new HashSet<String>();
		Map<Long, List<EvalAssignGroup>> assignGroupMap = new HashMap<Long, List<EvalAssignGroup>>();
		Map<String, Set<Long>> emailTemplateMap = new HashMap<String, Set<Long>>();
		Map<String, Date> earliestDueDate = new HashMap<String, Date>();
		long start, end;
		float seconds;
		
		if (log.isInfoEnabled())
			log.info("EvalEmailLogicImpl.sendEvalAvailableSingleEmail(): sending first notifications.");

		// get ids of evaluations that are active but haven't been announced yet
		start = System.currentTimeMillis();
		evalIds = dao.getActiveEvaluationIdsByAvailableEmailSent(Boolean.FALSE);
		end = System.currentTimeMillis();
		seconds = (end - start) / 1000;
		if (log.isInfoEnabled())
			log.info("EvalEmailLogicImpl.sendEvalAvailableSingleEmail(): getting active evaluation ids needing first notification"
					+ " took " + seconds + " seconds for " + evalIds.length + " evaluation ids.");

		if (evalIds.length > 0) {

			// get groups assigned to these evaluations
			start = System.currentTimeMillis();
			subsetGetAssignGroupsForEvals(assignGroupMap, evalIds);
			end = System.currentTimeMillis();
			seconds = (end - start) / 1000;
			if (log.isInfoEnabled())
				log.info("EvalEmailLogicImpl.sendEvalAvailableSingleEmail(): getting groups assigned to these evaluations"
						+ " took " + seconds + " seconds for " + evalIds.length + " evaluation ids.");
			if(streamUrl != null) {
				// ANNOUNCEMENT GROUPS
				reportGroups(streamUrl, assignGroupMap.size(), "announcementGroups");
			}
			else {
				log.error(this + ".sendEvalAvailableSingleEmail - TaskStatusServer stream url is null.");
			}
			start = System.currentTimeMillis();
			for (int i = 0; i < evalIds.length; i++) {
				
				// CT-697
				userIdsTakingEval.clear();
				groups = assignGroupMap.get(evalIds[i]);
				for (int j = 0; j < groups.size(); j++) {
					
					Set<String> users = evaluationService
					.getUserIdsTakingEvalInGroup(evalIds[i], groups
							.get(j).getEvalGroupId(),
							EvalConstants.EVAL_INCLUDE_ALL);
					
					// get the users in these groups
					userIdsTakingEval.addAll(users);

					// build a map of user id key and email template id list
					// value
					emailTemplatesByUser(evalIds[i], userIdsTakingEval,
							emailTemplateMap,
							EvalConstants.SINGLE_EMAIL_TEMPLATE_AVAILABLE,
							earliestDueDate);
					allUserIds.addAll(userIdsTakingEval);
				}
			}
			end = System.currentTimeMillis();
			seconds = (end - start) / 1000;
			if (log.isInfoEnabled())
				log.info("EvalEmailLogicImpl.sendEvalAvailableSingleEmail(): getting email templates associated with students"
						+ " took " + seconds + " seconds for " + evalIds.length + " evaluation ids.");

			// send email announcement (one email per email template in the
			// user's active evaluations)
			start = System.currentTimeMillis();
			if (!allUserIds.isEmpty())
				toAddresses = sendEvalSingleEmail(allUserIds, toAddresses, emailTemplateMap, earliestDueDate, streamUrl);
			end = System.currentTimeMillis();
			seconds = (end - start) / 1000;
			if (log.isInfoEnabled())
				log.info("EvalEmailLogicImpl.sendEvalAvailableSingleEmail(): queuing first notifications for delivery to " 
						+ allUserIds.size() + " user ids " + " took " + seconds + " seconds for "
						+ evalIds.length + " evaluation ids.");

			// set flag saying evaluation announcement was sent
			start = System.currentTimeMillis();
			evaluationService.setAvailableEmailSent(evalIds);
			seconds = (end - start) / 1000;
			if (log.isInfoEnabled())
				log.info("EvalEmailLogicImpl.sendEvalAvailableSingleEmail(): setting the 'available email sent' flag" 
						+ " took " + seconds + " seconds for " + evalIds.length + " evaluation ids.");
		}
		return toAddresses;
	}

	private String reportGroups(String streamUrl, int size, String entryTag) {
		String entryUrl = null;
		if(streamUrl != null) {
			String payload = (new Integer(size)).toString();
			entryUrl = evaluationService.newTaskStatusEntry(streamUrl,
					entryTag, TaskStatusStandardValues.RUNNING, payload);
		}
		else {
			log.error(this + ".reportGroups - TaskStatusService streamUrl is null. entryTag " + entryTag + " size " + new Integer(size));
		}
		return entryUrl;
	}

	/**
	 * Build a map of user id <K> earliest due date <V> for customizing email
	 * @param dueDate
	 * @param users
	 * @param earliestDueDate
	 */
   private void getEarliestDueDate(Date dueDate, Set<String> users,
		Map<String, Date> earliestDueDate) {
	   if(dueDate != null && users != null && earliestDueDate != null) {
		   Date earliest = null;
		   for(String userId: users) {
			   earliest = earliestDueDate.get(userId);
			   if(earliest == null) {
				   earliestDueDate.put(userId, dueDate);
			   }
			   else if (earliest.after(dueDate)) {
				   earliestDueDate.remove(userId);
				   earliestDueDate.put(userId, dueDate);
			   }
		   }
	   }
   }

   /*
    * (non-Javadoc)
    * @see org.sakaiproject.evaluation.logic.EvalEmailsLogic#sendEvalReminderSingleEmail()
    */
   public String[] sendEvalReminderSingleEmail(String streamUrl) {
		Long[] evalIds = new Long[] {};
		String[] toAddresses = new String[] {};
		List<EvalAssignGroup> groups = null;
		Set<String> allUserIds = new HashSet<String>();
		Set<String> userIdsTakingEval = new HashSet<String>();
		Map<Long, List<EvalAssignGroup>> assignGroupMap = new HashMap<Long, List<EvalAssignGroup>>();
		Map<String, Set<Long>> emailTemplateMap = new HashMap<String, Set<Long>>();
		Map<String, Date> earliestDueDate = new HashMap<String, Date>();
		long start, end;
		float seconds;
		
		if (log.isInfoEnabled())
			log.info("EvalEmailLogicImpl.sendEvalReminderSingleEmail(): sending reminders.");
		
		// get evaluations that are active and have been announced
		start = System.currentTimeMillis();
		evalIds = dao.getActiveEvaluationIdsByAvailableEmailSent(Boolean.TRUE);
		end = System.currentTimeMillis();
		seconds = (end - start)/1000;
		if (log.isInfoEnabled())
			log.info("EvalEmailLogicImpl.sendEvalReminderSingleEmail(): getting active evaluation ids needing reminders"
					+ " took " + seconds + " seconds for " + evalIds.length + " evaluation ids.");
		
		if (evalIds.length > 0) {
			
			// get groups assigned to these evaluations
			start = System.currentTimeMillis();
			// get groups in subsets to avoid query parameter size limit
			subsetGetAssignGroupsForEvals(assignGroupMap, evalIds);
			end = System.currentTimeMillis();
			seconds = (end - start) / 1000;
			if (log.isInfoEnabled())
				log.info("EvalEmailLogicImpl.sendEvalReminderSingleEmail(): getting groups assigned to these evaluations"
						+ " took " + seconds + " seconds for " + evalIds.length + " evaluation ids.");
			if(streamUrl != null) {
				// REMINDER GROUPS
				reportGroups(streamUrl, assignGroupMap.size(), "reminderGroups");
			}
			else {
				log.error(this + ".sendEvalReminderSingleEmail - TaskStatusService stream url is null.");
			}

			start = System.currentTimeMillis();
			for (int i = 0; i < evalIds.length; i++) {
				// CT-697
				userIdsTakingEval.clear();
				groups = assignGroupMap.get(evalIds[i]);
				for (int j = 0; j < groups.size(); j++) {
					
					// get the non-responders in these groups
					Set<String> users = evaluationService
					.getUserIdsTakingEvalInGroup(evalIds[i], groups
							.get(j).getEvalGroupId(),
							EvalConstants.EVAL_INCLUDE_NONTAKERS);

					// get the users in these groups
					userIdsTakingEval.addAll(users);

					//build maps of user id - email template id list & user id - earliest due date
					emailTemplatesByUser(evalIds[i], userIdsTakingEval,
							emailTemplateMap,
							EvalConstants.SINGLE_EMAIL_TEMPLATE_REMINDER,
							earliestDueDate);
					allUserIds.addAll(userIdsTakingEval);
				}
			}
			end = System.currentTimeMillis();
			seconds = (end - start) / 1000;
			if (log.isInfoEnabled())
				log.info("EvalEmailLogicImpl.sendEvalReminderSingleEmail(): getting email templates associated with students"
						+ " took " + seconds + " seconds for " + evalIds.length + " evaluation ids.");

			// send email announcement (one email per email template in the
			// user's active evaluations)
			start = System.currentTimeMillis();
			if (!allUserIds.isEmpty())
				toAddresses = sendEvalSingleEmail(allUserIds, toAddresses,emailTemplateMap, earliestDueDate, streamUrl);
			end = System.currentTimeMillis();
			seconds = (end - start) / 1000;
			if (log.isInfoEnabled())
				log.info("EvalEmailLogicImpl.sendEvalReminderSingleEmail(): queuing reminders for " 
						+ allUserIds.size() + " user ids " + " took " + seconds + " seconds for "
						+ evalIds.length + " evaluation ids.");
		}
		return toAddresses;
	}

/**
    * Build a map of user id key and email template id value
    * 
    * @param evalId the evaluation identifier
    * @param userIdsTakingEval the identifiers of users taking the evaluation
    * @param emailTemplateMap the collecting parameter pattern for the map of user id key and email template id value
    * @param type the type of email template from EvalConstants
    */
   private void emailTemplatesByUser(Long evaluationId, Set<String> userIds, 
		   Map<String, Set<Long>> emailTemplateMap, String type, Map<String, Date> earliestDueDate) {
	   
	   if(evaluationId == null || userIds == null || emailTemplateMap == null || type == null)
		   throw new IllegalArgumentException("emailTemplatesByUser parameter(s) null");
	   Long emailTemplateId = null;
	   Set<Long> emailTemplateIds = null;
	   EvalEvaluation evaluation = evaluationService.getEvaluationById(evaluationId);
	   Date dueDate = evaluation.getDueDate();
	   if(EvalConstants.SINGLE_EMAIL_TEMPLATE_REMINDER.equals(type)) {
		   emailTemplateId = evaluation.getReminderEmailTemplate().getId();
	   }
	   else if(EvalConstants.SINGLE_EMAIL_TEMPLATE_AVAILABLE.equals(type)) {
		   emailTemplateId = evaluation.getAvailableEmailTemplate().getId();
		   
	   }
	   for(String id : userIds) {
			// build the map of user and earliest due date
		   getEarliestDueDate(dueDate, userIds, earliestDueDate);
		   // build the map of user and email templates
		   emailTemplateIds = emailTemplateMap.get(id);
		   if(emailTemplateIds == null) {
			   emailTemplateIds = new HashSet<Long>();
			   emailTemplateIds.add(emailTemplateId);
			   emailTemplateMap.put(id, emailTemplateIds);
		   }
		   else {
			   emailTemplateIds.add(emailTemplateId);
		   }
	   }
   }

   /**
    * Dispatch one email per email template used for user's active evaluations 
    * based on EvalSettings.EMAIL_DELIVERY_OPTION (log, send, none).
    * 
    * @param uniqueIds the Sakai identities of the users to be notified
    * @param subjectConstant the email subject template
    * @param textConstant the email text template
    * @return an array of the email addresses to which email was sent
    */
   private String[] sendEvalSingleEmail(Set<String> uniqueIds, String[] toUserIds, Map<String, 
		   Set<Long>> emailTemplatesMap, Map<String, Date> earliestDates, String streamUrl) {
	   
		if (uniqueIds == null || toUserIds == null || emailTemplatesMap == null) {
			throw new IllegalArgumentException(
					"EvalEmailLogicImpl.sendEvalSingleEmail(): parameter(s) missing.");
		} else {
			if (log.isInfoEnabled())
				log.info("EvalEmailLogicImpl.sendEvalSingleEmail(): there are " + uniqueIds.size()
						+ " unique user ids in the single email queue.");
		}

		String from = (String) settings.get(EvalSettings.FROM_EMAIL_ADDRESS);
		String deliveryOption = (String) settings.get(EvalSettings.EMAIL_DELIVERY_OPTION);
		Boolean logEmailRecipients = (Boolean) settings.get(EvalSettings.LOG_EMAIL_RECIPIENTS);
		Integer batch = (Integer) settings.get(EvalSettings.EMAIL_BATCH_SIZE);
		Integer wait = (Integer) settings.get(EvalSettings.EMAIL_WAIT_INTERVAL);
		Integer every = (Integer) settings.get(EvalSettings.LOG_PROGRESS_EVERY);
		
		
		// check there is something to do
		if (deliveryOption.equals(EvalConstants.EMAIL_DELIVERY_NONE)
				&& !logEmailRecipients.booleanValue()) {
			if (log.isWarnEnabled())
				log.warn("EvalEmailLogicImpl.sendEvalSingleEmail(): EMAIL_DELIVERY_NONE and no logging of email recipients");
			return null;
		}

		String url = null, message = null, subject = null, earliest = null;
		
		Map<String, String> replacementValues = new HashMap<String, String>();
		List<String> sentToAddresses = new ArrayList<String>();
		Set<Long> emailTemplateIds = new HashSet<Long>();
		EvalEmailTemplate template = null;
		int numProcessed = 0;
		int start = 0;
		int sets = ((Integer)settings.get(EvalSettings.EMAIL_LOCKS_SIZE)).intValue();
		
		// a random starting number
		if(sets > 0) {
			start = new Random().nextInt(sets);
		}
		
		Date dueDate = null;
		// uniqueIds are user ids
		for (String s : uniqueIds) {
			try {
				replacementValues.clear();
				earliest = EvalConstants.NO_DATE_AVAILABLE;
				if(earliestDates.get(s) != null) {
					dueDate = earliestDates.get(s);
					earliest = DateFormat.getDateInstance().format(dueDate);  //e.g., Mar 17, 2008
				}
				replacementValues.put("EarliestEvalDueDate", earliest);
				// direct link to summary page of tool on My Worksite
				url = externalLogic.getMyWorkspaceUrl(s);
				replacementValues.put("MyWorkspaceDashboard", url);
				//earliestDueDate = evaluationService.getEarliestDueDate(s);
				
				replacementValues.put("HelpdeskEmail", from);
				//deprecated: EvalConstants.EVAL_TOOL_TITLE
				replacementValues.put("EvalToolTitle", externalLogic.getEvalToolTitle());
				replacementValues.put("EvalSite", EvalConstants.EVALUATION_TOOL_SITE);
				replacementValues.put("EvalCLE", EvalConstants.EVALUATION_CLE);

				//get the email template ids for this user
				emailTemplateIds = emailTemplatesMap.get(s);
				toUserIds = new String[] { s };
				
				for(Long i : emailTemplateIds) {
					//get the template
					template = evaluationService.getEmailTemplate(i);
					//make the substitutions
					message = makeEmailMessage(template.getMessage(),
							replacementValues);
					subject = makeEmailMessage(template.getSubject(),
							replacementValues);
					String lockName = getLockName(EvalConstants.EMAIL_LOCK_PREFIX);
	
					//dispatch email using delivery option
					if(EvalConstants.EMAIL_DELIVERY_SEND.equals(deliveryOption)) {
						String emailAddress = queueOutgoingEmail(s, subject, message, lockName);
						sentToAddresses.add(emailAddress);
					}
					else if(EvalConstants.EMAIL_DELIVERY_LOG.equals(deliveryOption)) {
						String emailAddress = logOutgoingEmail(from, s, subject, message, lockName);
						sentToAddresses.add(emailAddress);
					}
					else if (EvalConstants.EMAIL_DELIVERY_NONE.equals(deliveryOption)) {
						if(log.isInfoEnabled())
							log.info("Evaluation email delivery option is " + deliveryOption + ".");
					}
			         //TODO commonLogic.registerEntityEvent(EVENT_EMAIL_AVAILABLE, eval);
			         
			        // handle throttling email delivery and logging progress
					numProcessed = logEmailsProcessed(batch, wait, every, numProcessed, "queued");
				}
			} catch (Exception e) {
				String payload = "user id '" + s + "', url '" + url + "' " + e.toString();
				log.error("EvalEmailLogicImpl.sendEvalSingleEmail(): " + payload);
				if(streamUrl != null) {
					reportError(streamUrl, "error", "user id '" + s
							+ "', url '" + url + "' " + e);
				}
			}
		}
		// handle logging of total number of email messages processed
		if(metric.isInfoEnabled())
			metric.info("metric  EvalEmailLogicImpl.sendEvalSingleEmail(): total notifications queued " + numProcessed + ".");
		
		// handle logging of email recipients
		if(logEmailRecipients.booleanValue())
			logRecipients(sentToAddresses);
		if(log.isInfoEnabled())
			log.info("EvalEmailLogicImpl.sendEvalSingleEmail(): queuing of notifications is done.");

		String[] recipients = new String[sentToAddresses.size()];
		sentToAddresses.toArray(recipients);
		return recipients;
	}
   
   private String getLockName(String lockType) {
		StringBuffer buf = new StringBuffer();
		buf.append(lockType);
		int sets = ((Integer)settings.get(EvalSettings.EMAIL_LOCKS_SIZE)).intValue();
		
		// a random starting number
		int start = 0;
		if(sets > 0)
			start = new Random().nextInt(sets);

		String lockName = lockType + selectEmailSet(start, sets).toString();
			start++;
		return lockName;
   }
   
   /**
    * There are 0 - EVAL_CONFIG.EMAIL_LOCKS_SIZE sets to select from.
    * @param start
    * @param sets
    * @return
    */
   private Integer selectEmailSet(int start, int sets) {
	   if(sets == 0)
		   return 0;
		Integer set = start % sets;
		return set;
   }
   
   /*
    * (non-Javadoc)
    * @see org.sakaiproject.evaluation.logic.EvalEmailsLogic#sendEvalSubmissionConfirmationEmail(java.lang.Long)
    */
	public String sendEvalSubmissionConfirmationEmail(Long evaluationId) {
		String to = null;
		String message = null;
		String subject = null;
		String from = (String) settings.get(EvalSettings.FROM_EMAIL_ADDRESS);
		String deliveryOption = (String) settings.get(EvalSettings.EMAIL_DELIVERY_OPTION);
		String[] sentTo = new String[]{};
		if(!EvalConstants.EMAIL_DELIVERY_NONE.equals(deliveryOption)) {
			Boolean  sendConfirmation = (Boolean) settings.get(EvalSettings.ENABLE_SUBMISSION_CONFIRMATION_EMAIL);
			try {
				EvalUser user = externalLogic.getEvalUserById(externalLogic.getCurrentUserId());
				if(user != null && sendConfirmation.booleanValue()) {
					EvalEmailTemplate template = null;
					Map<String, String> replacementValues = new HashMap<String, String>();
					String[] sendTo = new String[]{user.email};
					String name = user.displayName;
					String toolName = externalLogic.getEvalToolTitle();
					EvalEvaluation eval = evaluationService.getEvaluationById(evaluationId);
					String evalTitle = eval.getTitle();
					Date date = new Date();
					String timeStamp = SettingsLogicUtils.getStringFromDate(date);
					replacementValues.put("UserName", name);
					replacementValues.put("EvalToolTitle", externalLogic.getEvalToolTitle());
					replacementValues.put("EvalTitle", evalTitle);
					replacementValues.put("TimeStamp", timeStamp);
					//get the template
					template = getConfirmationEmailTemplate();
					if(template != null) {
						//make the substitutions
						message = makeEmailMessage(template.getMessage(),
								replacementValues);
						subject = makeEmailMessage(template.getSubject(),
								replacementValues);
						to = user.email;
						if(EvalConstants.EMAIL_DELIVERY_LOG.equals(deliveryOption)) {
							if(log.isInfoEnabled()) {
								log.info("Submission Confirmation\nTo: " + to + 
										"\nFrom: " + from + "\nSubject: " + subject + "\nMessage:\n" + message + "\n\n");
							}
						}
						else if (EvalConstants.EMAIL_DELIVERY_SEND.equals(deliveryOption)) {
							sentTo = externalLogic.sendEmailsToAddresses(from, sendTo, subject, message, true);
						}
						else {
							log.error(this + ".sendEvalSubmissionConfirmationEmail(): invalid delivery option: " + deliveryOption);
						}
					}
				}
			}
			catch(Exception e) {
				log.error(this + ".sendEvalSubmissionConfirmationEmail(): " + e);
			}
		}
		return to;
	}
	
	/*
	 * (non-Javadoc)
	 * @see org.sakaiproject.evaluation.logic.EvalEmailsLogic#sendEvalTaskStatusEmail(java.lang.Boolean, java.lang.Boolean, java.util.List, java.util.List, java.lang.Integer)
	 */
	public void sendEvalTaskStatusEmail(Boolean jobRan, Boolean dataLoaded,
				List<String> ranText, List<String> loadedText, Integer unfinished) {
		if(jobRan == null || dataLoaded == null || ranText == null || loadedText == null)
			throw new IllegalArgumentException(this + ".sendEvalTaskStatusEmail argument(s) null.");
		String ran = null;
		String loaded = null;
		StringBuilder emailText = new StringBuilder();
		StringBuilder importText = new StringBuilder();
		if(jobRan) {
			ran = "ran";
			for(String line : ranText) {
				emailText.append(line + "\n");
			}
		}
		else {
			ran = "didn't run";
			emailText.append("Please check that the TQ Daily operational Summary Report job has been scheduled to run.");
		}
		if(dataLoaded) {
			loaded = "was";
			for(String line : loadedText) {
				importText.append(line + "\n\n");
			}
		}
		else {
			loaded = "was not";
			if(unfinished != null) {
				importText.append(unfinished.toString() + " files have not finished loading.");
			}
		}
		String to = externalLogic.getConfigurationSetting("taskstatus.email", "rwellis@umich.edu");
		String[] sendTo = (String[]) new String[] {to};
		String from = (String) settings.get(EvalSettings.FROM_EMAIL_ADDRESS);
		
		DateFormat formatter = new SimpleDateFormat("EEE, MMM d, ''yy h:mm a");
		Date date = new Date();
		String reportDate = formatter.format(date);
		
        // replace the text of the template with real values
        Map<String, String> replacementValues = new HashMap<String, String>();
        replacementValues.put("ReportDate", reportDate);
        replacementValues.put("Ran", ran);
        replacementValues.put("RanText", emailText.toString());
        replacementValues.put("Loaded", loaded);
        replacementValues.put("LoadedText", importText.toString());
 
		//get the template
		EvalEmailTemplate template = evaluationService.getTaskStatusEmailTemplate();
		//make the substitutions
		String subject = makeEmailMessage(template.getSubject(),
				replacementValues);
		String message = makeEmailMessage(template.getMessage(),
				replacementValues);
		if(log.isDebugEnabled()) {
			log.debug("sendEvalTaskStatusEmail: subject: " + subject);
			log.debug("sendEvalTaskStatusEmail: message: " + message);
		}
		String[] sentTo = externalLogic.sendEmailsToAddresses(from, sendTo, subject, message, true);
	}
 
   /**
    * Save pending email in a holding table for the email sending process(es) to read
    * 
    * @param from
    * @param toUserId
    * @param subject
    * @param message
    * @param lock
    * @return
    */
   private String queueOutgoingEmail(String toUserId, String subject, String message, String lock)  {
	   EvalUser recipient = externalLogic.getEvalUserById(toUserId);
	   EvalQueuedEmail email = new EvalQueuedEmail(lock, message, subject, recipient.email);
	   dao.save(email);
	   if(log.isDebugEnabled())
		   log.debug("EvalEmailLogicImpl.queueOutgoingEmail(): saved to the the notification holding table: " + email.toString());
	   return email.getToAddress();
   }
   
   private String logOutgoingEmail(String from, String toUserId, String subject, String message, String lock) {
	   EvalUser recipient = externalLogic.getEvalUserById(toUserId);
	   EvalQueuedEmail email = new EvalQueuedEmail(lock, message, subject, recipient.email);
	   if(log.isInfoEnabled()) {
		   log.info("To: " + email.getToAddress() + " From: " + from + " Subject: " + email.getSubject());
		   log.info("Lock: " + email.getLock() + " Message: " + email.getMessage());
	   }
	   return email.getToAddress();
   }
   
   /**
    * If logger for class is set with info level logging,
    * log a sorted list of email recipients at end of job,
    * 10 per line
    * 
    * @param sentToAddresses
    */
   private void logRecipients(List<String> sentToAddresses) {
	   if(sentToAddresses != null && !sentToAddresses.isEmpty()) {
		   Collections.sort(sentToAddresses);
		   StringBuffer sb = new StringBuffer();
		   String line = null;
		   int size = sentToAddresses.size();
		   int cnt = 0;
		   for(int i = 0; i < size; i++) {
			   if(cnt > 0)
				   sb.append(",");
			   sb.append((String)sentToAddresses.get(i));
			   cnt++;
			   if((i+1) % 10 == 0) {
				   line = sb.toString();
				   if(log.isInfoEnabled())
					   log.info("EvalEmailLogicImpl.logRecipients(): notification queued for " + line);
					//write a line and empty the buffer
					sb.setLength(0);
					cnt = 0;
				}
			}
			//if anything hasn't been written out do it now
			if(sb.length() > 0) {
				line = sb.toString();
				if (log.isInfoEnabled())
					log.info("EvalEmailLogicImpl.logRecipients(): notification queued for " + line);
			}
	   }
   }
   
   /**
    * Build a map of groups assigned to evaluations in successive calls to avoid a db query parameter size limit
    * 
    * @param assignGroupMap
    * @param evalIds
    */
   private void subsetGetAssignGroupsForEvals(Map<Long, List<EvalAssignGroup>>assignGroupMap, Long[] evalIds) {
	   List<Long> list = new ArrayList<Long>();
	   int size = evalIds.length;
	   int limit = 0;
	   // limit on parameter size
	   limit = setQueryLimit(limit);
	   for(int i = 0; i < size; i++) {
		   //add an id
		   list.add(evalIds[i]);
		   //if it's time to call the dao do it
		   if(list.size() == limit) {
			   Long[] sl = moveListToAssignGroupMap(assignGroupMap, list);
			   metric.info("EvalEmailLogicImpl.subsetGetAssignGroupsForEvals(): added " + sl.length + " assigned groups for evaluations.");
			   list.clear();
		   }
	   }
	   //add any that remain
	   if(!list.isEmpty()) {
		   Long[] sl = moveListToAssignGroupMap(assignGroupMap, list);
		   metric.info("EvalEmailLogicImpl.subsetGetAssignGroupsForEvals(): added " + sl.length + " assigned groups for evaluations.");
		   list.clear();
	   }
   }


	private int setQueryLimit(int limit) {
		try {
			   limit = Integer.parseInt(externalLogic.getConfigurationSetting(QUERY_LIMIT, DEFAULT_QUERY_LIMIT));
			   //ORA-01795: maximum number of expressions in a list is 1000
			   if(limit < 1 || limit > ORACLE_MAX_CLAUSES_IN_QUERY) {
				   limit = Integer.parseInt(DEFAULT_QUERY_LIMIT);
				   log.warn("populateAssignMapGroup() Oracle query limit value range error.");
			   }
		   }
		   catch (NumberFormatException e) {
			   log.error("populateAssignMapGroup() QUERY_LIMIT or DEFAULT_QUERY_LIMIT " +e);
			   limit = Integer.parseInt(DEFAULT_QUERY_LIMIT);
		   }
		return limit;
	}


	/**
	 * 
	 * @param assignGroupMap
	 * @param list
	 * @return
	 */
	private Long[] moveListToAssignGroupMap(
			Map<Long, List<EvalAssignGroup>> assignGroupMap, List<Long> list) {
		Long[] sl = (Long[]) list.toArray(new Long[0]);
		assignGroupMap.putAll(evaluationService.getAssignGroupsForEvals(sl,false, null));
		return sl;
	}

   /**
    * Periodically wait and/or log progress queuing and delivering
    * email if metric and/or logger for class is set with info level 
    * logging
    * 
    * @param batch the number of emails queued without a pause
    * @param wait the length of time in seconds to pause
    * @param modulo the interval for updating progress
    * @param numProcessed the number of emails processed
    * @return
    */
   private int logEmailsProcessed(Integer batch, Integer wait, Integer modulo,
		int numProcessed, String action) {
		numProcessed = numProcessed + 1;
		if(numProcessed > 0) {
			//periodic log message
			if(modulo != null && modulo.intValue() > 0) {
				if ((numProcessed % modulo.intValue()) == 0) {
					if(metric.isInfoEnabled())
						metric.info("metric  EvalEmailLogic.logEmailsProcessed: " + numProcessed + " notifications " + action + ".");
				}
			}
			//periodic wait
			if(batch != null && wait != null && batch.intValue() > 0 && wait.intValue() > 0) {
				if ((numProcessed % batch.intValue()) == 0) {
					if(log.isInfoEnabled())
						log.info("EvalEmailLogic.logEmailsProcessed: wait " + wait + " seconds.");
					try {
						Thread.sleep(wait * 1000);
					} catch (Exception e) {
						if (log.isErrorEnabled())
							log.error("Thread sleep interrupted.");
					}
				}
			}
		}
		return numProcessed;
	}
 
   /* (non-Javadoc)
    * @see org.sakaiproject.evaluation.logic.EvalEmailsLogic#sendEvalReminderNotifications(java.lang.Long, java.lang.String)
    */
   public String[] sendEvalReminderNotifications(Long evaluationId, String includeConstant) {
      log.debug("evaluationId: " + evaluationId + ", includeConstant: " + includeConstant);
      EvalUtils.validateEmailIncludeConstant(includeConstant);

      EvalEvaluation eval = getEvaluationOrFail(evaluationId);
      String from = getFromEmailOrFail(eval);
      EvalEmailTemplate emailTemplate = getEmailTemplateOrFail(EvalConstants.EMAIL_TEMPLATE_REMINDER, evaluationId);

      // get the associated eval groups for this evaluation
      // NOTE: this only returns the groups that should get emails, there is no need to do an additional check
      // to see if the instructor has opted in in this case -AZ
      Map<Long, List<EvalGroup>> evalGroupIds = evaluationService.getEvalGroupsForEval(new Long[] { evaluationId }, false, null);

      // only one possible map key so we can assume evaluationId
      List<EvalGroup> groups = evalGroupIds.get(evaluationId);
      if(log.isDebugEnabled())
    	  log.debug(groups.size() + " groups for available evaluation " + evaluationId + " were found.");

      List<String> sentEmails = new ArrayList<String>();
      // loop through groups and send emails to correct users in each
      for (int i = 0; i < groups.size(); i++) {
         EvalGroup group = (EvalGroup) groups.get(i);
         if (EvalConstants.GROUP_TYPE_INVALID.equals(group.type)) {
            continue; // skip processing for invalid groups
         }
         String evalGroupId = group.evalGroupId;

         Set<String> userIdsSet = evaluationService.getUserIdsTakingEvalInGroup(evaluationId, evalGroupId, includeConstant);
         if (userIdsSet.size() > 0) {
            // turn the set into an array
            String[] toUserIds = (String[]) userIdsSet.toArray(new String[] {});
            if(log.isDebugEnabled())
            	log.debug(toUserIds.length + " users recieving "
                  + EvalConstants.EMAIL_TEMPLATE_REMINDER + " notification to for available evaluation ("
                  + evaluationId + ") and group (" + group.evalGroupId + ")");

            // replace the text of the template with real values
            Map<String, String> replacementValues = new HashMap<String, String>();
            replacementValues.put("HelpdeskEmail", from);
            String message = makeEmailMessage(emailTemplate.getMessage(), eval, group, replacementValues);
            String subject = makeEmailMessage(emailTemplate.getSubject(), eval, group, replacementValues);

            // send the actual emails for this evalGroupId
            String[] emailAddresses = commonLogic.sendEmailsToUsers(from, toUserIds, subject, message, true);
            log.info("EvalEmailLogic reminders sent to " + emailAddresses.length + " users (attempted to send to "+toUserIds.length+")");
            // store sent emails to return
            for (int j = 0; j < emailAddresses.length; j++) {
               sentEmails.add(emailAddresses[j]);            
            }
            commonLogic.registerEntityEvent(EVENT_EMAIL_REMINDER, eval);
         }
      }

      return (String[]) sentEmails.toArray(new String[] {});
   }


   /* (non-Javadoc)
    * @see org.sakaiproject.evaluation.logic.EvalEmailsLogic#sendEvalResultsNotifications(java.lang.Long, boolean, boolean, java.lang.String)
    */
   public String[] sendEvalResultsNotifications(Long evaluationId, boolean includeEvaluatees,
         boolean includeAdmins, String jobType) {
      log.debug("evaluationId: " + evaluationId + ", includeEvaluatees: " + includeEvaluatees
            + ", includeAdmins: " + includeAdmins);

      /*TODO deprecated?
       if(EvalConstants.EVAL_INCLUDE_ALL.equals(includeConstant)) {
       }
       boolean includeEvaluatees = true;
       if (includeEvaluatees) {
       // TODO Not done yet
       log.error("includeEvaluatees Not implemented");
       }
       */

      EvalEvaluation eval = getEvaluationOrFail(evaluationId);
      String from = getFromEmailOrFail(eval);
      EvalEmailTemplate emailTemplate = getEmailTemplateOrFail(EvalConstants.EMAIL_TEMPLATE_RESULTS, evaluationId);

      // get the associated eval groups for this evaluation
      Map<Long, List<EvalGroup>> evalGroupIds = evaluationService.getEvalGroupsForEval(new Long[] { evaluationId }, false, null);
      // only one possible map key so we can assume evaluationId
      List<EvalGroup> groups = evalGroupIds.get(evaluationId);
      if (log.isDebugEnabled()) log.debug("Found " + groups.size() + " groups for available evaluation: " + evaluationId);
      Map<String, EvalGroup> groupsMap = new HashMap<String, EvalGroup>();
      for (EvalGroup evalGroup : groups) {
         groupsMap.put(evalGroup.evalGroupId, evalGroup);
      }

      // get the associated eval assign groups for this evaluation
      Map<Long, List<EvalAssignGroup>> evalAssignGroups = evaluationService.getAssignGroupsForEvals(new Long[] { evaluationId }, false, null);
      // only one possible map key so we can assume evaluationId
      List<EvalAssignGroup> assignGroups = evalAssignGroups.get(evaluationId);
      if (log.isDebugEnabled()) log.debug("Found " + assignGroups.size() + " assign groups for available evaluation: " + evaluationId);

      List<String> sentEmails = new ArrayList<String>();
      // loop through groups and send emails to correct users in each evalGroupId
      for (int i = 0; i < assignGroups.size(); i++) {
         EvalAssignGroup evalAssignGroup = assignGroups.get(i);
         String evalGroupId = evalAssignGroup.getEvalGroupId();
         EvalGroup group = groupsMap.get(evalGroupId);
         if ( group == null ||
               EvalConstants.GROUP_TYPE_INVALID.equals(group.type) ) {
            log.warn("Invalid group returned for groupId ("+evalGroupId+"), could not send results notifications");
            continue;
         }

         /*
          * Notification of results may occur on separate dates for owner,
          * instructors, and students. Job type is used to distinguish the
          * intended recipient group.
          */

         //always send results email to eval.getOwner()
         Set<String> userIdsSet = new HashSet<String>();
         if (jobType.equals(EvalConstants.JOB_TYPE_VIEWABLE)) {
            userIdsSet.add(eval.getOwner());
         }

         //if results are not private
         if (! EvalConstants.SHARING_PRIVATE.equals(eval.getResultsSharing()) ) {
            //at present, includeAdmins is always true
            if (includeAdmins && 
                  evalAssignGroup.getInstructorsViewResults().booleanValue() &&
                  jobType.equals(EvalConstants.JOB_TYPE_VIEWABLE_INSTRUCTORS)) {
               userIdsSet.addAll(commonLogic.getUserIdsForEvalGroup(evalGroupId,
                     EvalConstants.PERM_BE_EVALUATED));
            }

            //at present, includeEvaluatees is always true
            if (includeEvaluatees && 
                  evalAssignGroup.getStudentsViewResults().booleanValue() &&
                  jobType.equals(EvalConstants.JOB_TYPE_VIEWABLE_STUDENTS)) {
               userIdsSet.addAll(commonLogic.getUserIdsForEvalGroup(evalGroupId,
                     EvalConstants.PERM_TAKE_EVALUATION));
            }
         }

         if (userIdsSet.size() > 0) {
            // turn the set into an array
            String[] toUserIds = (String[]) userIdsSet.toArray(new String[] {});
            log.debug("Found " + toUserIds.length + " users (" + toUserIds + ") to send "
                  + EvalConstants.EMAIL_TEMPLATE_RESULTS + " notification to for available evaluation ("
                  + evaluationId + ") and group (" + evalGroupId + ")");

            // replace the text of the template with real values
            Map<String, String> replacementValues = new HashMap<String, String>();
            replacementValues.put("HelpdeskEmail", from);
            String message = makeEmailMessage(emailTemplate.getMessage(), eval, group, replacementValues);
            String subject = makeEmailMessage(emailTemplate.getSubject(), eval, group, replacementValues);

            // send the actual emails for this evalGroupId
            String[] emailAddresses = commonLogic.sendEmailsToUsers(from, toUserIds, subject, message, true);
            log.info("Sent evaluation results message to " + emailAddresses.length + " users (attempted to send to "+toUserIds.length+")");
            // store sent emails to return
            for (int j = 0; j < emailAddresses.length; j++) {
               sentEmails.add(emailAddresses[j]);            
            }
            commonLogic.registerEntityEvent(EVENT_EMAIL_RESULTS, eval);
         }
      }
      return (String[]) sentEmails.toArray(new String[] {});
   }



   // INTERNAL METHODS

   /**
    * INTERNAL METHOD<br/>
    * Builds the single email message from a template and a bunch of variables
    * (passed in and otherwise)
    * @param messageTemplate
    * @param replacementValues a map of String -> String representing $keys in the template to replace with text values
    * @return the processed message template with replacements and logic handled
    */
   private String makeEmailMessage(String messageTemplate, Map<String, String> replacementValues) {
	   replacementValues.put("URLtoSystem", externalLogic.getServerUrl());
	   return TextTemplateLogicUtils.processTextTemplate(messageTemplate, replacementValues);
   }

   /**
    * INTERNAL METHOD<br/>
    * Builds the email message from a template and a bunch of variables
    * (passed in and otherwise)
    * 
    * @param messageTemplate
    * @param eval
    * @param group
    * @param replacementValues a map of String -> String representing $keys in the template to replace with text values
    * @return the processed message template with replacements and logic handled
    */
   public String makeEmailMessage(String messageTemplate, EvalEvaluation eval, EvalGroup group,
         Map<String, String> replacementValues) {
      // replace the text of the template with real values
      if (replacementValues == null) {
         replacementValues = new HashMap<String, String>();
      }
      replacementValues.put("EvalTitle", eval.getTitle());

      // use a date which is related to the current users locale
      DateFormat df = DateFormat.getDateInstance(DateFormat.MEDIUM, 
            commonLogic.getUserLocale(commonLogic.getCurrentUserId()));

      replacementValues.put("EvalStartDate", df.format(eval.getStartDate()));
      String dueDate = "--------";
      if (eval.getDueDate() != null) {
         dueDate = df.format(eval.getDueDate());
      }
      replacementValues.put("EvalDueDate", dueDate);
      String viewDate = null;
      if (eval.getViewDate() != null) {
         viewDate = df.format(eval.getDueDate());
      } else {
         viewDate = dueDate;
      }
      replacementValues.put("EvalResultsDate", viewDate);
      replacementValues.put("EvalGroupTitle", group.title);

      // ensure that the if-then variables are set to false if they are unset
      if (! replacementValues.containsKey("ShowAddItemsText")) {
         replacementValues.put("ShowAddItemsText", "false");
      }
      if (! replacementValues.containsKey("ShowOptInText")) {
         replacementValues.put("ShowOptInText", "false");
      }
      if (! replacementValues.containsKey("ShowOptOutText")) {
         replacementValues.put("ShowOptOutText", "false");
      }

      // generate URLs to the evaluation
      String evalEntityURL = null;
      if (group != null && group.evalGroupId != null) {
         // get the URL directly to the evaluation with group context included
         Long assignGroupId = evaluationService.getAssignGroupId(eval.getId(), group.evalGroupId);
         EvalAssignGroup assignGroup = evaluationService.getAssignGroupById(assignGroupId);
         if (assignGroup != null) {
            evalEntityURL = commonLogic.getEntityURL(assignGroup);
         }
      }

      if (evalEntityURL == null) {
         // just get the URL to the evaluation without group context
         evalEntityURL = commonLogic.getEntityURL(eval);
      }

      // all URLs are identical because the user permissions determine access uniquely
      replacementValues.put("URLtoTakeEval", evalEntityURL);
      replacementValues.put("URLtoAddItems", evalEntityURL);
      replacementValues.put("URLtoOptIn", evalEntityURL);
      replacementValues.put("URLtoOptOut", evalEntityURL);
      replacementValues.put("URLtoViewResults", evalEntityURL);
      replacementValues.put("URLtoSystem", commonLogic.getServerUrl());

      return TextTemplateLogicUtils.processTextTemplate(messageTemplate, replacementValues);
   }
   
   public EvalEmailTemplate getConfirmationEmailTemplate() {
	   EvalEmailTemplate template = null;
	   template = evaluationService.getConfirmationEmailTemplate();
	   return template;
   }

   /**
    * INTERNAL METHOD<br/>
    * Get an email template by type and evaluationId or fail
    * @param typeConstant an EvalConstants.EMAIL_TEMPLATE constant
    * @param evaluationId unique id of an eval or null to only get the default template
    * @return an email template
    * @throws IllegalStateException if no email template can be found
    */
   public EvalEmailTemplate getEmailTemplateOrFail(String typeConstant, Long evaluationId) {
      EvalEmailTemplate emailTemplate = null;
      if (evaluationId != null &&
            ( EvalConstants.EMAIL_TEMPLATE_AVAILABLE.equals(typeConstant) ||
                  EvalConstants.EMAIL_TEMPLATE_REMINDER.equals(typeConstant) ) ) {
         // get the template from the evaluation itself
         EvalEmailTemplate evalEmailTemplate = evaluationService.getEmailTemplate(evaluationId, typeConstant);
         if (evalEmailTemplate != null) {
            emailTemplate = evalEmailTemplate;
         }
      }
      if (emailTemplate == null) {
         // get the default email template
         try {
            emailTemplate = evaluationService.getDefaultEmailTemplate(typeConstant);
         } catch (RuntimeException e) {
            log.error("Failed to get default email template ("+typeConstant+"): " + e.getMessage());
            emailTemplate = null;
         }
      }
      if (emailTemplate == null) {
         throw new IllegalArgumentException("Cannot find email template default or in eval ("+evaluationId+"): " + typeConstant);
      }
      return emailTemplate;
   }

   /**
    * INTERNAL METHOD<br/>
    * Get the email address from system settings or the evaluation
    * @param eval
    * @return an email address
    * @throws IllegalStateException if a from address cannot be found
    */
   public String getFromEmailOrFail(EvalEvaluation eval) {
      String from = (String) settings.get(EvalSettings.FROM_EMAIL_ADDRESS);
      if (eval.getReminderFromEmail() != null && ! "".equals(eval.getReminderFromEmail())) {
         from = eval.getReminderFromEmail();
      }
      if (from == null) {
         throw new IllegalStateException("Could not get a from email address from system settings or the evaluation");
      }
      return from;
   }

   /**
    * INTERNAL METHOD<br/>
    * Gets the evaluation or throws exception,
    * reduce code duplication
    * @param evaluationId
    * @return eval for this id
    * @throws IllegalArgumentException if no eval exists
    */
   protected EvalEvaluation getEvaluationOrFail(Long evaluationId) {
      EvalEvaluation eval = evaluationService.getEvaluationById(evaluationId);
      if (eval == null) {
         throw new IllegalArgumentException("Cannot find evaluation with id: " + evaluationId);
      }
      return eval;
   }

}
