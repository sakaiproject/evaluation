/**
 * Copyright (c) 2005-2020 The Apereo Foundation
 *
 * Licensed under the Educational Community License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *             http://opensource.org/licenses/ecl2
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.sakaiproject.evaluation.tool.locators;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import uk.org.ponder.beanutil.BeanResolver;


/**
 * This turns normal line breaks into <br/> in a string
 * 
 * @author Aaron Zeckoski (aaron@caret.cam.ac.uk)
 */
public class LineBreakResolver implements BeanResolver {

   Pattern lineBreaks = Pattern.compile("(\r\n|\r|\n|\n\r)");
   String htmlBR = "<br/>";

   /* (non-Javadoc)
    * @see uk.org.ponder.beanutil.BeanResolver#resolveBean(java.lang.Object)
    */
   public String resolveBean(Object bean) {
      String text = bean.toString();
      Matcher m = lineBreaks.matcher(text);
      return m.replaceAll(htmlBR);
   }

}
