/**
 * Copyright 2015-2016 The OpenZipkin Authors
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
package zipkin.server;

import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.WebRequest;
import zipkin.Codec;
import zipkin.Span;
import zipkin.storage.QueryRequest;
import zipkin.storage.StorageComponent;

import static org.springframework.http.MediaType.APPLICATION_JSON_VALUE;
import static zipkin.internal.Util.lowerHexToUnsignedLong;

/**
 * Implements the json api used by the Zipkin UI
 *
 * See com.twitter.zipkin.query.ZipkinQueryController
 */
@RestController
@RequestMapping("/api/v1")
public class ZipkinQueryApiV1 {

  @Autowired
  @Value("${zipkin.query.lookback:86400000}")
  int defaultLookback = 86400000; // 7 days in millis

  private final StorageComponent storage;

  @Autowired
  public ZipkinQueryApiV1(StorageComponent storage) {
    this.storage = storage; // don't cache spanStore here as it can cause the app to crash!
  }

  @RequestMapping(value = "/dependencies", method = RequestMethod.GET, produces = APPLICATION_JSON_VALUE)
  public byte[] getDependencies(@RequestParam(value = "endTs", required = true) long endTs,
                                @RequestParam(value = "lookback", required = false) Long lookback) {
    return Codec.JSON.writeDependencyLinks(storage.spanStore().getDependencies(endTs, lookback != null ? lookback : defaultLookback));
  }

  @RequestMapping(value = "/services", method = RequestMethod.GET)
  public List<String> getServiceNames() {
    return storage.spanStore().getServiceNames();
  }

  @RequestMapping(value = "/spans", method = RequestMethod.GET)
  public List<String> getSpanNames(
      @RequestParam(value = "serviceName", required = true) String serviceName) {
    return storage.spanStore().getSpanNames(serviceName);
  }

  @RequestMapping(value = "/traces", method = RequestMethod.GET, produces = APPLICATION_JSON_VALUE)
  public byte[] getTraces(
      @RequestParam(value = "serviceName", required = false) String serviceName,
      @RequestParam(value = "spanName", defaultValue = "all") String spanName,
      @RequestParam(value = "annotationQuery", required = false) String annotationQuery,
      @RequestParam(value = "minDuration", required = false) Long minDuration,
      @RequestParam(value = "maxDuration", required = false) Long maxDuration,
      @RequestParam(value = "endTs", required = false) Long endTs,
      @RequestParam(value = "lookback", required = false) Long lookback,
      @RequestParam(value = "limit", required = false) Integer limit) {
    QueryRequest queryRequest = QueryRequest.builder()
        .serviceName(serviceName)
        .spanName(spanName)
        .parseAnnotationQuery(annotationQuery)
        .minDuration(minDuration)
        .maxDuration(maxDuration)
        .endTs(endTs)
        .lookback(lookback != null ? lookback : defaultLookback)
        .limit(limit).build();

    return Codec.JSON.writeTraces(storage.spanStore().getTraces(queryRequest));
  }

  @RequestMapping(value = "/trace/{traceId}", method = RequestMethod.GET, produces = APPLICATION_JSON_VALUE)
  public byte[] getTrace(@PathVariable String traceId, WebRequest request) {
    long id = lowerHexToUnsignedLong(traceId);
    String[] raw = request.getParameterValues("raw"); // RequestParam doesn't work for param w/o value
    List<Span> trace = raw != null ? storage.spanStore().getRawTrace(id) : storage.spanStore().getTrace(id);

    if (trace == null) {
      throw new TraceNotFoundException(traceId, id);
    }
    return Codec.JSON.writeSpans(trace);
  }

  @ExceptionHandler(TraceNotFoundException.class)
  @ResponseStatus(HttpStatus.NOT_FOUND)
  public void notFound() {
  }

  static class TraceNotFoundException extends RuntimeException {
    public TraceNotFoundException(String traceId, long id) {
      super("Cannot find trace for id=" + traceId + ", long value=" + id);
    }
  }
}