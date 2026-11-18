package io.kitchensink.adapter.out.h2;

import java.util.List;
import java.util.Optional;

import io.kitchensink.member.application.port.out.MemberRepository;
import io.kitchensink.member.domain.Member;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class H2MemberAdapter implements MemberRepository {
    private final H2MemberRepository repository;

    @Override
    public List<Member> findAllOrderedByName() {
        return repository.findAllByOrderByNameAsc().stream().map(H2MemberAdapter::toMember).toList();
    }

    @Override
    public Optional<Member> findById(long id) {
        return repository.findById(id).map(H2MemberAdapter::toMember);
    }

    private static Member toMember(MemberEntity entity) {
        return new Member(entity.getId(), entity.getName(), entity.getEmail(), entity.getPhoneNumber());
    }
}
