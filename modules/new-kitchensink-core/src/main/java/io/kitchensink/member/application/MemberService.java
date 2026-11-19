package io.kitchensink.member.application;

import java.util.List;
import java.util.Optional;

import io.kitchensink.member.application.port.in.QueryMembers;
import io.kitchensink.member.application.port.out.MemberRepository;
import io.kitchensink.member.domain.Member;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class MemberService implements QueryMembers {
    private final MemberRepository repository;

    @Override
    public List<Member> list() {
        return repository.findAllOrderedByName();
    }

    @Override
    public Optional<Member> findById(long id) {
        return repository.findById(id);
    }
}
