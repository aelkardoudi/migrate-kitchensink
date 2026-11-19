package io.kitchensink.adapter.out.h2;

import java.util.List;
import java.util.Optional;

import io.kitchensink.member.application.port.out.MemberRepository;
import io.kitchensink.member.domain.DuplicateMemberEmailException;
import io.kitchensink.member.domain.Member;
import lombok.RequiredArgsConstructor;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Repository;

@Repository
@Profile("!mongo")
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

    @Override
    public void insert(String name, String email, String phoneNumber) {
        if (repository.existsByEmail(email)) {
            throw new DuplicateMemberEmailException();
        }
        try {
            repository.saveAndFlush(new MemberEntity(name, email, phoneNumber));
        } catch (DataIntegrityViolationException exception) {
            // H2 reports the backing index of the email constraint when two inserts race.
            if (exception.getCause() instanceof ConstraintViolationException violation
                    && violation.getConstraintName() != null
                    && violation.getConstraintName().startsWith("PUBLIC.MEMBER_EMAIL_UNIQUE_INDEX_")) {
                throw new DuplicateMemberEmailException();
            }
            throw exception;
        }
    }

    private static Member toMember(MemberEntity entity) {
        return new Member(entity.getId(), entity.getName(), entity.getEmail(), entity.getPhoneNumber());
    }
}
