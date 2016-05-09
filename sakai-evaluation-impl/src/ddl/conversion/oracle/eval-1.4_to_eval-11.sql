--
-- Copyright 2003 Sakai Foundation Licensed under the
-- Educational Community License, Version 2.0 (the "License"); you may
-- not use this file except in compliance with the License. You may
-- obtain a copy of the License at
--
-- http://www.osedu.org/licenses/ECL-2.0
--
-- Unless required by applicable law or agreed to in writing,
-- software distributed under the License is distributed on an "AS IS"
-- BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
-- or implied. See the License for the specific language governing
-- permissions and limitations under the License.
--

-- Oracle conversion script - 1.4 to 11

create table EVAL_HIERARCHY_RULE (
    ID number(19,0) not null,
    NODE_ID number(19,0) not null,
    RULE varchar(255) not null,
    OPT varchar(10) not null,
    primary key (ID)
);
