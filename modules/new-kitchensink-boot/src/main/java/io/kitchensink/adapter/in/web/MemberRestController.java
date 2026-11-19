package io.kitchensink.adapter.in.web;

import java.util.List;

import io.kitchensink.member.application.port.in.QueryMembers;
import io.kitchensink.member.application.port.in.RegisterMember;
import io.kitchensink.member.domain.Member;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping(path = "/rest/members", produces = MediaType.APPLICATION_JSON_VALUE)
public class MemberRestController {
    private final QueryMembers members;
    private final RegisterMember registration;

    @GetMapping
    List<Member> list() {
        return members.list();
    }

    @GetMapping("/{id:[0-9]+}")
    ResponseEntity<Member> findById(@PathVariable String id) {
        final long memberId;
        try {
            memberId = Long.parseLong(id);
        } catch (NumberFormatException ignored) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.of(members.findById(memberId));
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Void> register(@Valid @RequestBody MemberRegistrationRequest request) {
        registration.register(request.name(), request.email(), request.phoneNumber());
        return ResponseEntity.ok().build();
    }
}
