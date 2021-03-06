/**
 * Copyright 2018 The Jaeger Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package io.jaegertracing.tests.model;

import java.util.List;

public class Data {

    private List<Span> spans;
    private String traceID;

    public List<Span> getSpans() {
        return spans;
    }

    public String getTraceID() {
        return traceID;
    }

    public void setSpans(List<Span> spans) {
        this.spans = spans;
    }

    public void setTraceID(String traceID) {
        this.traceID = traceID;
    }
}
