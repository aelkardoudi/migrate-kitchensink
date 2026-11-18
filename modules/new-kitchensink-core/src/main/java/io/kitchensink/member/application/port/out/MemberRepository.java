package io.kitchensink.member.application.port.out;

import java.util.List;
import java.util.Optional;

import io.kitchensink.member.domain.Member;

public interface MemberRepository {
    List<Member> findAllOrderedByName();

    Optional<Member> findById(long id);

    void insert(String name, String email, String phoneNumber);
}
