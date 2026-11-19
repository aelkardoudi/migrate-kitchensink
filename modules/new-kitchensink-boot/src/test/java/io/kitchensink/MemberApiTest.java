package io.kitchensink;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

import io.kitchensink.adapter.in.web.MemberRestController;
import io.kitchensink.member.application.MemberService;
import io.kitchensink.member.application.port.out.MemberRepository;
import io.kitchensink.member.domain.DuplicateMemberEmailException;
import io.kitchensink.member.domain.Member;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(MemberRestController.class)
@Import(MemberService.class)
class MemberApiTest {
    private static final String CONTEXT_PATH = "/kitchensink";
    private static final String MEMBERS = CONTEXT_PATH + "/rest/members";
    private static final Member ALPHA = new Member(0L, "Alpha", "alpha@example.test", "0123456789");

    @Autowired
    private MockMvc api;

    @MockitoBean
    private MemberRepository members;

    @Test
    void returnsTheSortedListWithHomonyms() throws Exception {
        when(members.findAllOrderedByName()).thenReturn(List.of(ALPHA,
                new Member(2L, "Alpha", "other-alpha@example.test", "0333333333"),
                new Member(3L, "Zulu", "zulu@example.test", "0555555555")));

        api.perform(get(MEMBERS).contextPath(CONTEXT_PATH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].name", contains("Alpha", "Alpha", "Zulu")))
                .andExpect(jsonPath("$[*].id", containsInAnyOrder(0, 2, 3)));
    }

    @Test
    void findsAMemberByNumericIdAndPreservesPhoneNumberLeadingZero() throws Exception {
        when(members.findById(0L)).thenReturn(Optional.of(ALPHA));

        api.perform(get(MEMBERS + "/0").contextPath(CONTEXT_PATH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(0))
                .andExpect(jsonPath("$.name").value(ALPHA.name()))
                .andExpect(jsonPath("$.email").value(ALPHA.email()))
                .andExpect(jsonPath("$.phoneNumber").value(ALPHA.phoneNumber()));
    }

    @Test
    void returnsNotFoundForAbsentAndOutOfRangeIds() throws Exception {
        when(members.findById(42L)).thenReturn(Optional.empty());

        api.perform(get(MEMBERS + "/42").contextPath(CONTEXT_PATH)).andExpect(status().isNotFound());
        api.perform(get(MEMBERS + "/9223372036854775808").contextPath(CONTEXT_PATH))
                .andExpect(status().isNotFound());
    }

    @Test
    void registrationPassesOnlyMemberFieldsAndReturnsAnEmptyResponse() throws Exception {
        api.perform(post(MEMBERS).contextPath(CONTEXT_PATH).contentType(MediaType.APPLICATION_JSON).content("""
                {"id":0,"name":"Élodie أمينة","email":"elodie@example.test","phoneNumber":"0123456789"}
                """))
                .andExpect(status().isOk())
                .andExpect(content().string(""));

        verify(members).insert("Élodie أمينة", "elodie@example.test", "0123456789");
    }

    @Test
    void reportsValidationErrorsBeforeCallingTheRepository() throws Exception {
        api.perform(post(MEMBERS).contextPath(CONTEXT_PATH).locale(Locale.ENGLISH)
                .contentType(MediaType.APPLICATION_JSON).content("""
                {"name":"Bad1","email":null,"phoneNumber":"01234ABCDE"}
                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.name").value("Must not contain numbers"))
                .andExpect(jsonPath("$.email").isNotEmpty())
                .andExpect(jsonPath("$.phoneNumber").isNotEmpty());

        verifyNoInteractions(members);
    }

    @Test
    void mapsDuplicateEmailToConflict() throws Exception {
        doThrow(new DuplicateMemberEmailException()).when(members)
                .insert("Appropriate Name", ALPHA.email(), ALPHA.phoneNumber());

        api.perform(post(MEMBERS).contextPath(CONTEXT_PATH).contentType(MediaType.APPLICATION_JSON).content("""
                {"name":"Appropriate Name","email":"alpha@example.test","phoneNumber":"0123456789"}
                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.email").value("Email taken"));
    }
}
