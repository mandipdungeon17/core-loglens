package com.honeywell.loglens.controller;

import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.honeywell.loglens.config.LogLensConfig;
import com.honeywell.loglens.model.LogEntry;
import com.honeywell.loglens.model.SearchRequest;
import com.honeywell.loglens.service.LogSearchService;
import com.honeywell.loglens.service.LogSearchService.FileInfo;
import com.honeywell.loglens.service.LogSearchService.SearchResult;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(LogController.class)
class LogControllerTest {

  @Autowired private MockMvc mockMvc;

  @Autowired private ObjectMapper objectMapper;

  @MockitoBean private LogSearchService searchService;

  @MockitoBean private LogLensConfig config;

  @Test
  void search_validRequest_returns200() throws Exception {
    LogEntry entry =
        LogEntry.builder()
            .service("routing")
            .timestamp(LocalDateTime.of(2025, 1, 15, 10, 30, 0))
            .level("ERROR")
            .message("Something went wrong")
            .build();

    SearchResult result =
        new SearchResult(
            List.of(entry), // entries
            1, // totalMatched
            1, // filteredByStructured
            500, // limit
            "desc", // sortOrder
            Collections.emptyMap(), // nextOffsets
            "BACKWARD", // strategy
            null, // searchId
            0 // totalCached
            );

    when(searchService.search(any(SearchRequest.class))).thenReturn(result);

    SearchRequest req = new SearchRequest();
    req.setLevel("ERROR");
    req.setLimit(500);

    mockMvc
        .perform(
            post("/api/logs/search")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalMatched").value(1))
        .andExpect(jsonPath("$.entries", hasSize(1)))
        .andExpect(jsonPath("$.entries[0].level").value("ERROR"))
        .andExpect(jsonPath("$.sortOrder").value("desc"))
        .andExpect(jsonPath("$.strategy").value("BACKWARD"));
  }

  @Test
  void search_emptyBody_returns400() throws Exception {
    mockMvc
        .perform(post("/api/logs/search").contentType(MediaType.APPLICATION_JSON).content(""))
        .andExpect(status().isBadRequest());
  }

  @Test
  void services_returnsServiceList() throws Exception {
    Map<String, Object> svc = new LinkedHashMap<>();
    svc.put("name", "routing");
    svc.put("color", "#1a6fa3");
    svc.put("logFile", "/honeywell/logs/routing.log");
    svc.put("exists", false);
    svc.put("sizeKb", 0L);
    svc.put("lastModified", "N/A");

    when(searchService.serviceStatus()).thenReturn(List.of(svc));

    mockMvc
        .perform(get("/api/logs/services"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$", hasSize(1)))
        .andExpect(jsonPath("$[0].name").value("routing"))
        .andExpect(jsonPath("$[0].color").value("#1a6fa3"))
        .andExpect(jsonPath("$[0].logFile").value("/honeywell/logs/routing.log"));
  }

  @Test
  void serverTime_returnsIsoFormat() throws Exception {
    mockMvc
        .perform(get("/api/logs/servertime"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.serverTime").exists())
        .andExpect(jsonPath("$.serverTime").isString())
        .andExpect(
            jsonPath("$.serverTime", matchesPattern("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}")));
  }

  @Test
  void health_returnsOk() throws Exception {
    mockMvc
        .perform(get("/api/logs/health"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("UP"))
        .andExpect(jsonPath("$.app").value("loglens"));
  }

  @Test
  void clearCache_postsSuccessfully() throws Exception {
    doNothing().when(searchService).clearAllCaches();

    mockMvc
        .perform(post("/api/logs/cache/clear"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("cleared"));

    verify(searchService, times(1)).clearAllCaches();
  }

  @Test
  void search_responseIncludesSearchId() throws Exception {
    SearchResult result =
        new SearchResult(
            List.of(), // entries
            0, // totalMatched
            50, // filteredByStructured
            500, // limit
            "desc", // sortOrder
            Collections.emptyMap(), // nextOffsets
            "BACKWARD", // strategy
            "abc-123-xyz", // searchId
            50 // totalCached
            );

    when(searchService.search(any(SearchRequest.class))).thenReturn(result);

    SearchRequest req = new SearchRequest();
    req.setLevel("INFO");

    mockMvc
        .perform(
            post("/api/logs/search")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.searchId").value("abc-123-xyz"))
        .andExpect(jsonPath("$.totalCached").value(50))
        .andExpect(jsonPath("$.filteredByStructured").value(50));
  }

  @Test
  void search_illegalArgument_returns400() throws Exception {
    when(searchService.search(any(SearchRequest.class)))
        .thenThrow(new IllegalArgumentException("bad param"));

    SearchRequest req = new SearchRequest();

    mockMvc
        .perform(
            post("/api/logs/search")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("Invalid search request"));
  }

  @Test
  void search_unexpectedException_returns500() throws Exception {
    when(searchService.search(any(SearchRequest.class)))
        .thenThrow(new RuntimeException("disk failure"));

    SearchRequest req = new SearchRequest();

    mockMvc
        .perform(
            post("/api/logs/search")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
        .andExpect(status().isInternalServerError())
        .andExpect(jsonPath("$.error").value("Search failed"));
  }

  // ── Historical endpoint tests ───────────────────────────────

  @Test
  void listFiles_returnsFileList() throws Exception {
    List<FileInfo> files =
        List.of(
            new FileInfo("routing-console.log.1", 1024, "2025-01-15T10:00:00Z"),
            new FileInfo("routing-console.log.2.gz", 512, "2025-01-14T10:00:00Z"));
    when(searchService.listServiceFiles("routing")).thenReturn(files);

    mockMvc
        .perform(get("/api/logs/services/routing/files"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$", hasSize(2)))
        .andExpect(jsonPath("$[0].name").value("routing-console.log.1"))
        .andExpect(jsonPath("$[0].sizeKb").value(1024))
        .andExpect(jsonPath("$[1].name").value("routing-console.log.2.gz"));
  }

  @Test
  void listFiles_unknownService_returnsEmptyList() throws Exception {
    when(searchService.listServiceFiles("unknown")).thenReturn(Collections.emptyList());

    mockMvc
        .perform(get("/api/logs/services/unknown/files"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$", hasSize(0)));
  }

  @Test
  void searchHistorical_validRequest_returns200() throws Exception {
    SearchResult result =
        new SearchResult(
            List.of(), 0, 0, 500, "desc", Collections.emptyMap(), "BACKWARD", "hist-abc-123", 0);
    when(searchService.searchHistorical(
            eq("routing"), eq("routing-console.log.1"), any(SearchRequest.class)))
        .thenReturn(result);

    SearchRequest req = new SearchRequest();
    req.setLevel("ERROR");

    mockMvc
        .perform(
            post("/api/logs/search/historical")
                .param("serviceName", "routing")
                .param("fileName", "routing-console.log.1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.searchId").value("hist-abc-123"))
        .andExpect(jsonPath("$.strategy").value("BACKWARD"));
  }

  @Test
  void searchHistorical_invalidFileName_returns400() throws Exception {
    when(searchService.searchHistorical(
            eq("routing"), eq("../etc/passwd"), any(SearchRequest.class)))
        .thenThrow(new IllegalArgumentException("Invalid file name"));

    SearchRequest req = new SearchRequest();

    mockMvc
        .perform(
            post("/api/logs/search/historical")
                .param("serviceName", "routing")
                .param("fileName", "../etc/passwd")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("Invalid file name"));
  }

  @Test
  void searchHistorical_ioError_returns500() throws Exception {
    when(searchService.searchHistorical(
            eq("routing"), eq("broken.log.1"), any(SearchRequest.class)))
        .thenThrow(new IOException("Permission denied"));

    SearchRequest req = new SearchRequest();

    mockMvc
        .perform(
            post("/api/logs/search/historical")
                .param("serviceName", "routing")
                .param("fileName", "broken.log.1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
        .andExpect(status().isInternalServerError())
        .andExpect(jsonPath("$.error").value("Search failed due to an internal I/O error."));
  }

  // ── Input validation tests ─────────────────────────────────

  @Test
  void search_invalidSearchId_returns400() throws Exception {
    SearchRequest req = new SearchRequest();
    req.setSearchId("invalid.id/with!chars");

    mockMvc
        .perform(
            post("/api/logs/search")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("Invalid searchId"));
  }

  @Test
  void searchHistorical_invalidServiceName_returns400() throws Exception {
    SearchRequest req = new SearchRequest();

    mockMvc
        .perform(
            post("/api/logs/search/historical")
                .param("serviceName", "bad/service\nname")
                .param("fileName", "valid.log.1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("Invalid service name"));
  }

  @Test
  void searchHistorical_invalidSearchId_returns400() throws Exception {
    SearchRequest req = new SearchRequest();
    req.setSearchId("bad.id!");

    mockMvc
        .perform(
            post("/api/logs/search/historical")
                .param("serviceName", "routing")
                .param("fileName", "routing-console.log.1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("Invalid searchId"));
  }
}
