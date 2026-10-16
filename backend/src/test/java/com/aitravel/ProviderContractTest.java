package com.aitravel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Uses a local HTTP server, never calls paid external services. */
class ProviderContractTest {
    HttpServer server;
    Json json;
    TravelProviders providers;
    TravelStore store;
    List<JsonNode> requests=new CopyOnWriteArrayList<>();
    boolean emptySearch;
    boolean fabricatedSource;
    boolean searchError;
    boolean malformedSearch;
    TripInput input=new TripInput("上海","杭州",LocalDate.now().plusDays(2),1,2,new BigDecimal("500"),List.of("自然风光"),"情侣",List.of("西湖"),"");
    @BeforeEach void setup() throws Exception {
        json=new Json(new ObjectMapper().registerModule(new JavaTimeModule()));store=mock(TravelStore.class);
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/web-search",exchange->{
            var body=json.read(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));requests.add(body);
            assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isEqualTo("Bearer test-search");
            assertThat(body.path("count").asInt()).isEqualTo(8);
            assertThat(body.path("summary").asBoolean()).isTrue();
            assertThat(body.path("freshness").asText()).isEqualTo("noLimit");
            assertThat(body.has("search_depth")).isFalse();
            var pages=emptySearch?List.of():List.of(Map.of("name","杭州西湖资料","url","https://example.org/west-lake","summary","西湖位于杭州，开放式区域免费。","snippet","简短摘要","datePublished","2026-10-01","dateLastCrawled","2026-10-16"));
            var response=searchError?Map.of("code",403,"message","insufficient balance"):malformedSearch?Map.of("code",200,"data",Map.of()):Map.of("code",200,"log_id","bocha-test-log","data",Map.of("webPages",Map.of("value",pages)));
            byte[] bytes=json.write(response).getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
        });
        server.createContext("/chat/completions",exchange->{
            var body=json.read(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));requests.add(body);
            assertThat(body.path("model").asText()).isEqualTo("deepseek-v4-pro");assertThat(body.has("tools")).isFalse();
            assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isEqualTo("Bearer test-model");
            var candidate=new LinkedHashMap<String,Object>();candidate.put("name","西湖");candidate.put("city","杭州");candidate.put("district","西湖区");candidate.put("ticket_price",0);candidate.put("source_ids",List.of(fabricatedSource?"FAKE":"S1"));candidate.put("price_source_ids",List.of("S1"));candidate.put("aliases",List.of());
            var content=json.write(Map.of("candidates",List.of(candidate)));
            byte[] bytes=json.write(Map.of("id","mock-request","usage",Map.of("prompt_tokens",100,"completion_tokens",50),"choices",List.of(Map.of("finish_reason","stop","message",Map.of("content",content))))).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
        });server.start();
        String url="http://127.0.0.1:"+server.getAddress().getPort();
        var settings=new TravelSettings("live",url,"test-model","deepseek-v4-pro",url,"test-search",2,8,3,120,240,"","","1","2","0.01","CNY");
        providers=new TravelProviders(settings,store,json,new ResultValidator(json));
    }
    @AfterEach void close() { server.stop(0); }
    @Test void usesActualSearchSourcesAndKeepsModelToolsDisabled() {
        var research=providers.research(1,1,input,System.currentTimeMillis()+10000);
        assertThat(research.path("candidates").size()).isEqualTo(1);assertThat(research.path("sources").size()).isEqualTo(1);
        assertThat(research.path("sources").path(0).path("url").asText()).isEqualTo("https://example.org/west-lake");
        assertThat(research.path("sources").path(0).path("title").asText()).isEqualTo("杭州西湖资料");
        assertThat(research.path("sources").path(0).path("excerpt").asText()).contains("开放式区域免费");
        assertThat(research.path("sources").path(0).path("published_at").asText()).isEqualTo("2026-10-01");
        assertThat(research.path("must_visit_mapping").path("西湖").asText()).isEqualTo("C1");
        assertThat(requests).hasSize(3); // Two bounded search queries and one normalization model call.
        verify(store,times(3)).update(startsWith("INSERT INTO api_log"),any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any());
        var calls=org.mockito.Mockito.mockingDetails(store).getInvocations().stream().filter(i->i.getMethod().getName().equals("update")).toList();
        var logged=new ArrayList<Object[]>();
        for(var call:calls) { Object[] args=call.getArguments(); if(args.length==2 && args[1] instanceof Object[] values) logged.add(values); else logged.add(Arrays.copyOfRange(args,1,args.length)); }
        var searchLog=logged.stream().filter(args->"SEARCH".equals(args[3])).findFirst().orElseThrow();
        assertThat(searchLog[4]).isEqualTo("Bocha");assertThat(searchLog[10]).isEqualTo(BigDecimal.ONE);
        assertThat(searchLog[11]).isEqualTo("bocha-test-log");assertThat((BigDecimal)searchLog[12]).isEqualByComparingTo("0.01");
    }
    @Test void emptySearchDoesNotCallModel() {
        emptySearch=true;
        assertThatThrownBy(()->providers.research(1,1,input,System.currentTimeMillis()+10000)).isInstanceOf(ApiError.class).hasMessageContaining("检索来源");
        assertThat(requests).hasSize(2);assertThat(requests.stream().anyMatch(r->r.has("model"))).isFalse();
    }
    @Test void inventedSourceIdsCannotCreateCandidates() {
        fabricatedSource=true;
        assertThatThrownBy(()->providers.research(1,1,input,System.currentTimeMillis()+10000)).isInstanceOf(ApiError.class).hasMessageContaining("可用景点");
    }
    @Test void businessErrorDoesNotCallModelOrReportKnownSearchCost() {
        searchError=true;
        assertThatThrownBy(()->providers.research(1,1,input,System.currentTimeMillis()+10000)).isInstanceOf(ApiError.class).hasMessageContaining("权限或额度");
        assertThat(requests).hasSize(1);
        var invocation=org.mockito.Mockito.mockingDetails(store).getInvocations().stream().filter(i->i.getMethod().getName().equals("update")).findFirst().orElseThrow();
        Object[] all=invocation.getArguments();Object[] args=all.length==2 && all[1] instanceof Object[] values?values:Arrays.copyOfRange(all,1,all.length);
        assertThat(args[6]).isEqualTo("SEARCH_PROVIDER_ERROR");assertThat(args[10]).isNull();assertThat(args[12]).isNull();assertThat(args[13]).isEqualTo("UNKNOWN");
    }
    @Test void unexpectedSearchSchemaDoesNotCallModel() {
        malformedSearch=true;
        assertThatThrownBy(()->providers.research(1,1,input,System.currentTimeMillis()+10000)).isInstanceOf(ApiError.class).hasMessageContaining("网页结果结构");
        assertThat(requests).hasSize(1);
    }
}
