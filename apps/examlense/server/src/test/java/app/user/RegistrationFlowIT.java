package app.user;

import app.AbstractIntegrationTest;
import app.examination.Examination;
import app.examination.ExaminationRepository;
import com.jayway.jsonpath.JsonPath;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Open registration: anyone can start using ExamLense without being invited, and
 * still only ever sees their own exams. The cap on account creation is the part
 * that keeps that from being a way to farm unlimited LLM quota.
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = "app.auth.registrations-per-ip-per-day=3")
class RegistrationFlowIT extends AbstractIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired ExaminationRepository exams;

    /** Distinct source addresses, so one test's registrations don't exhaust another's cap. */
    private String uniqueIp() {
        return "10." + (int) (Math.random() * 250) + "." + (int) (Math.random() * 250) + "."
            + (int) (Math.random() * 250);
    }

    private String registerFrom(String ip) throws Exception {
        String json = mvc.perform(post("/api/auth/register").with(r -> {
                r.setRemoteAddr(ip);
                return r;
            }))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        return JsonPath.read(json, "$.token");
    }

    @Test
    void aFirstTimeVisitorGetsAnAccountWithoutBeingInvited() throws Exception {
        String token = registerFrom(uniqueIp());

        mvc.perform(get("/api/me").header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.is_admin").value(false))
            // No TUM ID yet — a generated handle stands in until they link one.
            .andExpect(jsonPath("$.has_tum_id").value(false))
            .andExpect(jsonPath("$.external_id").value(
                org.hamcrest.Matchers.startsWith(UserService.ANON_PREFIX)));
    }

    @Test
    void registrationNeedsNoCredentialOfItsOwn() throws Exception {
        mvc.perform(post("/api/auth/register").with(r -> {
                r.setRemoteAddr(uniqueIp());
                return r;
            }))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.token").exists());
    }

    @Test
    void eachRegistrationIsASeparateAccountWithItsOwnEmptyDashboard() throws Exception {
        String ip = uniqueIp();
        String first = registerFrom(ip);
        String second = registerFrom(ip);

        String firstId = JsonPath.read(
            mvc.perform(get("/api/me").header("Authorization", "Bearer " + first))
                .andReturn().getResponse().getContentAsString(), "$.id");

        Examination theirs = new Examination();
        theirs.setOwnerId(UUID.fromString(firstId));
        theirs.setTitle("private");
        theirs.setSource("manual");
        theirs.setStatus("draft");
        exams.save(theirs);

        mvc.perform(get("/api/exams").header("Authorization", "Bearer " + second))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$").isEmpty());
        mvc.perform(get("/api/exams/" + theirs.getId()).header("Authorization", "Bearer " + second))
            .andExpect(status().isForbidden());
    }

    /**
     * The load-bearing abuse control. Without it, clearing browser storage mints a
     * fresh account with a fresh LLM quota, so per-user metering would bound
     * nothing and the provider bill would be open-ended.
     */
    @Test
    void registrationIsCappedPerIpSoQuotaCannotBeFarmed() throws Exception {
        String ip = uniqueIp();
        registerFrom(ip);
        registerFrom(ip);
        registerFrom(ip);

        mvc.perform(post("/api/auth/register").with(r -> {
                r.setRemoteAddr(ip);
                return r;
            }))
            .andExpect(status().isTooManyRequests());
    }

    @Test
    void theCapIsPerIpNotGlobal() throws Exception {
        String exhausted = uniqueIp();
        registerFrom(exhausted);
        registerFrom(exhausted);
        registerFrom(exhausted);

        mvc.perform(post("/api/auth/register").with(r -> {
                r.setRemoteAddr(exhausted);
                return r;
            }))
            .andExpect(status().isTooManyRequests());

        // A different network is unaffected.
        registerFrom(uniqueIp());
    }

    @Test
    void theStoredIpIsHashedNotTheAddressItself() throws Exception {
        String ip = uniqueIp();
        registerFrom(ip);

        assertThat(users.findAll())
            .filteredOn(u -> u.getCreatedIpHash() != null)
            .allSatisfy(u -> assertThat(u.getCreatedIpHash()).doesNotContain(ip));
    }

    // --- Linking a TUM ID ---------------------------------------------------

    private String linkBody(String tumId) {
        return "{\"external_id\":\"%s\"}".formatted(tumId);
    }

    @Test
    void linkingATumIdReplacesTheGeneratedHandle() throws Exception {
        String token = registerFrom(uniqueIp());
        String tumId = "link" + UUID.randomUUID().toString().substring(0, 6);

        mvc.perform(patch("/api/me").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(linkBody(tumId.toUpperCase() + "@tum.de")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.external_id").value(tumId))
            .andExpect(jsonPath("$.has_tum_id").value(true));
    }

    /**
     * A TUM ID is what a TUM login will match on, so two accounts must never claim
     * one — otherwise the cutover hands a stranger's exams to whichever the IdP
     * resolves to.
     */
    @Test
    void aTumIdAlreadyLinkedElsewhereIsRefused() throws Exception {
        String tumId = "taken" + UUID.randomUUID().toString().substring(0, 6);
        mvc.perform(patch("/api/me").header("Authorization", "Bearer " + registerFrom(uniqueIp()))
                .contentType(MediaType.APPLICATION_JSON).content(linkBody(tumId)))
            .andExpect(status().isOk());

        mvc.perform(patch("/api/me").header("Authorization", "Bearer " + registerFrom(uniqueIp()))
                .contentType(MediaType.APPLICATION_JSON).content(linkBody(tumId)))
            .andExpect(status().isConflict());
    }

    @Test
    void relinkingTheSameTumIdToTheSameAccountIsFine() throws Exception {
        String token = registerFrom(uniqueIp());
        String tumId = "same" + UUID.randomUUID().toString().substring(0, 6);

        mvc.perform(patch("/api/me").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(linkBody(tumId)))
            .andExpect(status().isOk());
        mvc.perform(patch("/api/me").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(linkBody(tumId)))
            .andExpect(status().isOk());
    }

    @Test
    void anAnonHandleCannotBeLinkedAsIfItWereATumId() throws Exception {
        mvc.perform(patch("/api/me").header("Authorization", "Bearer " + registerFrom(uniqueIp()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(linkBody(UserService.ANON_PREFIX + "abcdef")))
            .andExpect(status().isBadRequest());
    }

    @Test
    void linkingRequiresAToken() throws Exception {
        mvc.perform(patch("/api/me").contentType(MediaType.APPLICATION_JSON).content(linkBody("ab12cde")))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void linkingKeepsTheAccountAndItsExaminations() throws Exception {
        String token = registerFrom(uniqueIp());
        String id = JsonPath.read(
            mvc.perform(get("/api/me").header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString(), "$.id");

        mvc.perform(patch("/api/me").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(linkBody("keep" + UUID.randomUUID().toString().substring(0, 6))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(id));
    }
}
