/* Copyright 2014  Nest Labs

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.  */

package nest.sparkle.util

import org.slf4j.LoggerFactory

import com.typesafe.scalalogging.slf4j.Logger

/** Adds a lazy val 'log' of type com.typesafe.scalalogging.slf4j.Logger to the class into which this trait is mixed.
  */
trait Log {
  lazy val log: Logger = {
    val loggerName = getClass.getName
    val slfjLogger = LoggerFactory.getLogger(loggerName)
    val log = LoggerFactory.getLogger("nest.sparkle.util.Log")
    log.trace(s"initialized logger $slfjLogger")
    Logger(slfjLogger)
  }
}
