/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.spark.sql.internal

/** A source of configuration values. */
trait GlutenConfigProvider {
  def get(key: String): Option[String]
}

class SQLConfProvider(conf: SQLConf) extends GlutenConfigProvider {
  override def get(key: String): Option[String] = Option(conf.settings.get(key))
}

class MapProvider(conf: Map[String, String]) extends GlutenConfigProvider {
  override def get(key: String): Option[String] = conf.get(key)
}
