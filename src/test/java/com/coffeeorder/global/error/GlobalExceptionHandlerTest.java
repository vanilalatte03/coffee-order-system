package com.coffeeorder.global.error;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.coffeeorder.global.config.TimeConfig;
import com.coffeeorder.global.observability.TraceIdFilter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.sql.SQLException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@WebMvcTest(controllers = GlobalExceptionHandlerTest.FixtureController.class)
@Import({
    GlobalExceptionHandler.class,
    TraceIdFilter.class,
    TimeConfig.class,
    GlobalExceptionHandlerTest.FixtureController.class
})
@ExtendWith(OutputCaptureExtension.class)
@DisplayName("전역 예외 처리기")
class GlobalExceptionHandlerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @DisplayName("필드 검증 오류를 공통 오류와 fieldErrors로 변환한다")
    @Test
    void field_validation을_공통_오류와_fieldErrors로_변환한다() throws Exception {
        mockMvc.perform(
                        post("/test-fixture/requests")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value("요청 값이 올바르지 않습니다."))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("amount"))
                .andExpect(jsonPath("$.fieldErrors[0].reason").value("1 이상의 정수여야 합니다."));
    }

    @DisplayName("잘못된 JSON을 내부 예외 노출 없이 공통 오류로 변환한다")
    @Test
    void malformed_JSON을_내부_예외_노출_없이_공통_오류로_변환한다() throws Exception {
        mockMvc.perform(
                        post("/test-fixture/requests")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"amount\":"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value("요청 값이 올바르지 않습니다."))
                .andExpect(jsonPath("$.fieldErrors").doesNotExist());
    }

    @DisplayName("알 수 없는 JSON 필드를 공통 오류로 거절한다")
    @Test
    void 알_수_없는_JSON_필드를_공통_오류로_거절한다() throws Exception {
        mockMvc.perform(
                        post("/test-fixture/requests")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"amount\":1,\"unexpected\":true}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value("요청 값이 올바르지 않습니다."))
                .andExpect(jsonPath("$.fieldErrors").doesNotExist());
    }

    @DisplayName("오류 응답, 헤더, 요청 로그가 같은 traceId를 사용한다")
    @Test
    void 오류_응답과_헤더와_요청_로그가_같은_traceId를_사용한다(CapturedOutput output) throws Exception {
        MvcResult result =
                mockMvc.perform(
                                post("/test-fixture/requests")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("{}"))
                        .andExpect(status().isBadRequest())
                        .andExpect(header().exists(TraceIdFilter.TRACE_ID_HEADER))
                        .andReturn();

        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsByteArray());
        String traceId = body.path("traceId").asText();

        assertThat(traceId).isNotBlank();
        assertThat(result.getResponse().getHeader(TraceIdFilter.TRACE_ID_HEADER))
                .isEqualTo(traceId);
        assertThat(output).contains("traceId=" + traceId);
    }

    @DisplayName("예기치 않은 예외는 내부 메시지를 응답하지 않는다")
    @Test
    void 예기치_않은_예외는_내부_메시지를_응답하지_않는다() throws Exception {
        mockMvc.perform(get("/test-fixture/failure"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_SERVER_ERROR"))
                .andExpect(jsonPath("$.message").value("서버 오류가 발생했습니다."))
                .andExpect(jsonPath("$.fieldErrors").doesNotExist())
                .andExpect(jsonPath("$..password").doesNotExist());
    }

    @DisplayName("DB 잠금 시간 초과는 503과 재시도 헤더로 변환한다")
    @Test
    void DB_락_timeout은_503과_재시도_헤더로_변환한다() throws Exception {
        mockMvc.perform(get("/test-fixture/lock-timeout"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "1"))
                .andExpect(jsonPath("$.code").value("CONCURRENCY_TIMEOUT"));
    }

    @DisplayName("DB 연결 실패는 SQL을 응답과 로그에서 숨긴 503으로 변환한다")
    @Test
    void DB_연결_실패는_SQL을_응답과_로그에서_숨긴_503으로_변환한다(CapturedOutput output) throws Exception {
        mockMvc.perform(get("/test-fixture/database-unavailable"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "1"))
                .andExpect(jsonPath("$.code").value("DATABASE_UNAVAILABLE"))
                .andExpect(jsonPath("$.message").value("데이터베이스를 일시적으로 사용할 수 없습니다."))
                .andExpect(
                        content()
                                .string(
                                        org.hamcrest.Matchers.not(
                                                org.hamcrest.Matchers.containsString("SELECT"))))
                .andExpect(
                        content()
                                .string(
                                        org.hamcrest.Matchers.not(
                                                org.hamcrest.Matchers.containsString(
                                                        "secret_table"))));
        assertThat(output)
                .doesNotContain("SELECT password FROM secret_table", "database-password-secret");
    }

    @DisplayName("정상, 비즈니스 거절, 서버 오류는 각 요청의 traceId를 로그와 응답에 유지한다")
    @Test
    void 정상_비즈니스_거절_서버_오류는_각_요청의_traceId를_로그와_응답에_유지한다(CapturedOutput output) throws Exception {
        MvcResult normal = mockMvc.perform(get("/test-fixture/success")).andReturn();
        MvcResult business = mockMvc.perform(get("/test-fixture/business-rejection")).andReturn();
        MvcResult server = mockMvc.perform(get("/test-fixture/failure")).andReturn();

        String normalTrace = normal.getResponse().getHeader(TraceIdFilter.TRACE_ID_HEADER);
        String businessTrace =
                objectMapper
                        .readTree(business.getResponse().getContentAsByteArray())
                        .path("traceId")
                        .asText();
        String serverTrace =
                objectMapper
                        .readTree(server.getResponse().getContentAsByteArray())
                        .path("traceId")
                        .asText();

        assertThat(normalTrace).isNotBlank();
        assertThat(business.getResponse().getHeader(TraceIdFilter.TRACE_ID_HEADER))
                .isEqualTo(businessTrace);
        assertThat(server.getResponse().getHeader(TraceIdFilter.TRACE_ID_HEADER))
                .isEqualTo(serverTrace);
        assertThat(output)
                .contains(
                        "traceId=" + normalTrace,
                        "traceId=" + businessTrace,
                        "traceId=" + serverTrace,
                        "resultCode=SUCCESS",
                        "resultCode=" + ErrorCode.USER_NOT_FOUND.code(),
                        "resultCode=" + ErrorCode.INTERNAL_SERVER_ERROR.code());
        assertThat(output)
                .doesNotContain(
                        "request-body-secret",
                        "response-body-secret",
                        "pointBalance=",
                        "idempotencySnapshot=",
                        "database-password-secret");
    }

    @RestController
    @RequestMapping("/test-fixture")
    public static class FixtureController {

        @PostMapping("/requests")
        void validate(@Valid @RequestBody FixtureRequest request) {}

        @GetMapping("/failure")
        void fail() {
            throw new IllegalStateException("database password must never be exposed");
        }

        @GetMapping("/lock-timeout")
        void lockTimeout() {
            throw new CannotAcquireLockException("forced lock timeout");
        }

        @GetMapping("/database-unavailable")
        void databaseUnavailable() {
            throw new CannotGetJdbcConnectionException(
                    "SELECT password FROM secret_table",
                    new SQLException("database-password-secret", "08001"));
        }

        @GetMapping("/success")
        String success() {
            return "ok";
        }

        @GetMapping("/business-rejection")
        void businessRejection() {
            throw new com.coffeeorder.domain.user.service.UserNotFoundException(999);
        }
    }

    public record FixtureRequest(@NotNull(message = "1 이상의 정수여야 합니다.") Long amount) {}
}
