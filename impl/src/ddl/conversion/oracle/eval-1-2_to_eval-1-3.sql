/* Oracle conversion script */
create table EVAL_ASSIGN_USER (
	ID number(19,0) not null,
	EID varchar2(255 char), 
	LAST_MODIFIED timestamp(6) not null,
	OWNER varchar2(255 char) not null,
	USER_ID varchar2(255 char) not null,
	GROUP_ID varchar2(255 char) not null,
	ASSIGN_TYPE varchar2(255 char) not null,
	ASSIGN_STATUS varchar2(255 char) not null,
	LIST_ORDER  number(10,0) not null,
	ASSIGN_GROUP_ID number(19,0) not null,
    EVALUATION_FK NUMBER(19,0) not null,
	primary key (ID)
);

create index ASSIGN_USER_EVALUATION_FKC on EVAL_ASSIGN_USER (EVALUATION_FK);
create unique index ASSIGN_USER_MUL_IDX on EVAL_ASSIGN_USER (USER_ID,GROUP_ID,ASSIGN_TYPE,EVALUATION_FK);

alter table EVAL_ASSIGN_USER  
	add constraint ASSIGN_USER_EVALUATION_FKC 
	foreign key (EVALUATION_FK) 
	references eval_evaluation (ID);

alter table EVAL_ASSIGN_GROUP add ( SELECTION_SETTINGS VARCHAR2(2000 CHAR) );
alter table EVAL_EVALUATION add ( EMAIL_OPEN_NOTIFICATION number(1,0) ); 
alter table EVAL_EVALUATION add ( REMINDER_STATUS varchar2(255) );
alter table EVAL_EVALUATION add ( SELECTION_SETTINGS VARCHAR2(2000 CHAR) );
alter table EVAL_ITEM add ( COMPULSORY number(1,0) );
alter table EVAL_RESPONSE add ( SELECTIONS_CODE clob );
alter table EVAL_TEMPLATEITEM add ( COMPULSORY number(1,0) );

insert into EVAL_CONFIG (ID,LAST_MODIFIED, NAME, VALUE) VALUES (hibernate_sequence.NEXTVAL,CURRENT_TIMESTAMP(6),'ALLOW_EVALSPECIFIC_TOGGLE_EMAIL_NOTIFICATION',0);
insert into EVAL_CONFIG (ID,LAST_MODIFIED, NAME, VALUE) VALUES (hibernate_sequence.NEXTVAL,CURRENT_TIMESTAMP(6),'DEFAULT_EMAIL_REMINDER_FREQUENCY',0);
insert into EVAL_CONFIG (ID,LAST_MODIFIED, NAME, VALUE) VALUES (hibernate_sequence.NEXTVAL,CURRENT_TIMESTAMP(6),'DISABLE_ITEM_BANK',0);
insert into EVAL_CONFIG (ID,LAST_MODIFIED, NAME, VALUE) VALUES (hibernate_sequence.NEXTVAL,CURRENT_TIMESTAMP(6),'DISABLE_QUESTION_BLOCKS',0);
insert into EVAL_CONFIG (ID,LAST_MODIFIED, NAME, VALUE) VALUES (hibernate_sequence.NEXTVAL,CURRENT_TIMESTAMP(6),'ENABLE_EVAL_TERM_IDS',0);
insert into EVAL_CONFIG (ID,LAST_MODIFIED, NAME, VALUE) VALUES (hibernate_sequence.NEXTVAL,CURRENT_TIMESTAMP(6),'ENABLE_FILTER_ASSIGNABLE_GROUPS',0);
insert into EVAL_CONFIG (ID,LAST_MODIFIED, NAME, VALUE) VALUES (hibernate_sequence.NEXTVAL,CURRENT_TIMESTAMP(6),'ENABLE_JOB_COMPLETION_EMAIL',0);
insert into EVAL_CONFIG (ID,LAST_MODIFIED, NAME, VALUE) VALUES (hibernate_sequence.NEXTVAL,CURRENT_TIMESTAMP(6),'ENABLE_LIST_OF_TAKERS_EXPORT',1);
insert into EVAL_CONFIG (ID,LAST_MODIFIED, NAME, VALUE) VALUES (hibernate_sequence.NEXTVAL,CURRENT_TIMESTAMP(6),'ENABLE_PROVIDER_SYNC',0);
insert into EVAL_CONFIG (ID,LAST_MODIFIED, NAME, VALUE) VALUES (hibernate_sequence.NEXTVAL,CURRENT_TIMESTAMP(6),'ENABLE_REMINDER_STATUS',0);
insert into EVAL_CONFIG (ID,LAST_MODIFIED, NAME, VALUE) VALUES (hibernate_sequence.NEXTVAL,CURRENT_TIMESTAMP(6),'ENABLE_SINGLE_EMAIL_PER_STUDENT',0);
insert into EVAL_CONFIG (ID,LAST_MODIFIED, NAME, VALUE) VALUES (hibernate_sequence.NEXTVAL,CURRENT_TIMESTAMP(6),'EVALUATION_TIME_TO_WAIT_SECS',300);
insert into EVAL_CONFIG (ID,LAST_MODIFIED, NAME, VALUE) VALUES (hibernate_sequence.NEXTVAL,CURRENT_TIMESTAMP(6),'SINGLE_EMAIL_REMINDER_DAYS',0);
insert into EVAL_CONFIG (ID,LAST_MODIFIED, NAME, VALUE) VALUES (hibernate_sequence.NEXTVAL,CURRENT_TIMESTAMP(6),'SYNC_UNASSIGNED_GROUPS_ON_STARTUP',1);
insert into EVAL_CONFIG (ID,LAST_MODIFIED, NAME, VALUE) VALUES (hibernate_sequence.NEXTVAL,CURRENT_TIMESTAMP(6),'SYNC_USER_ASSIGNMENTS_ON_GROUP_SAVE',1);
insert into EVAL_CONFIG (ID,LAST_MODIFIED, NAME, VALUE) VALUES (hibernate_sequence.NEXTVAL,CURRENT_TIMESTAMP(6),'SYNC_USER_ASSIGNMENTS_ON_GROUP_UPDATE',0);
insert into EVAL_CONFIG (ID,LAST_MODIFIED, NAME, VALUE) VALUES (hibernate_sequence.NEXTVAL,CURRENT_TIMESTAMP(6),'SYNC_USER_ASSIGNMENTS_ON_STATE_CHANGE',1);


insert into EVAL_CONFIG (ID,LAST_MODIFIED, NAME, VALUE) VALUES (hibernate_sequence.NEXTVAL,CURRENT_TIMESTAMP(6),'ENABLE_ASSISTANT_CATEGORY', 0);                                                                                                                                                                                                                                       
insert into EVAL_CONFIG (ID,LAST_MODIFIED, NAME, VALUE) VALUES (hibernate_sequence.NEXTVAL,CURRENT_TIMESTAMP(6),'ENABLE_EVALUATEE_BOX', 0);                                                                                                                                                                                                                                            
insert into EVAL_CONFIG (ID,LAST_MODIFIED, NAME, VALUE) VALUES (hibernate_sequence.NEXTVAL,CURRENT_TIMESTAMP(6),'ENABLE_INSTRUCTOR_ASSISTANT_SELECTION', 0);                                                                                                                                                                                                                           
