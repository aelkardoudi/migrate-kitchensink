package io.kitchensink.member.application.port.in;

import java.util.List;
import java.util.Optional;

import io.kitchensink.member.domain.Member;

public interface QueryMembers {
    List<Member> list();

    Optional<Member> findById(long id);
}
