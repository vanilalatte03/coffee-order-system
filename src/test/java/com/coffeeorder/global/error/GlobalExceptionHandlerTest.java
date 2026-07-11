package com.coffeeorder.global.error;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.coffeeorder.global.config.TimeConfig;
import com.coffeeorder.global.observability.TraceIdFilter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.http.MediaType;
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
class GlobalExceptionHandlerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

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

    @Test
    void 예기치_않은_예외는_내부_메시지를_응답하지_않는다() throws Exception {
        mockMvc.perform(get("/test-fixture/failure"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_SERVER_ERROR"))
                .andExpect(jsonPath("$.message").value("서버 오류가 발생했습니다."))
                .andExpect(jsonPath("$.fieldErrors").doesNotExist())
                .andExpect(jsonPath("$..password").doesNotExist());
    }

    @Test
    void DB_락_timeout은_503과_재시도_헤더로_변환한다() throws Exception {
        mockMvc.perform(get("/test-fixture/lock-timeout"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "1"))
                .andExpect(jsonPath("$.code").value("CONCURRENCY_TIMEOUT"));
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
    }

    public record FixtureRequest(@NotNull(message = "1 이상의 정수여야 합니다.") Long amount) {}
}
