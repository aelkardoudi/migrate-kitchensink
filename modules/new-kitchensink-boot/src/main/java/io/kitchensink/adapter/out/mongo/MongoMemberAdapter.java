package io.kitchensink.adapter.out.mongo;

import java.util.List;
import java.util.Optional;

import io.kitchensink.member.application.port.out.MemberRepository;
import io.kitchensink.member.domain.DuplicateMemberEmailException;
import io.kitchensink.member.domain.Member;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;

@Repository
@Profile("mongo")
@RequiredArgsConstructor
public class MongoMemberAdapter implements MemberRepository {
    private final MongoMemberRepository repository;
    private final MongoMemberIds ids;

    @Override
    public List<Member> findAllOrderedByName() {
        return repository.findAllByOrderByNameAsc().stream().map(MemberDocument::toMember).toList();
    }

    @Override
    public Optional<Member> findById(long id) {
        return repository.findById(id).map(MemberDocument::toMember);
    }

    @Override
    public void insert(String name, String email, String phoneNumber) {
        if (repository.existsByEmail(email)) {
            throw new DuplicateMemberEmailException();
        }
        long id = ids.nextId();
        try {
            repository.insert(new MemberDocument(id, name, email, phoneNumber));
        } catch (DuplicateKeyException exception) {
            // The unique index also covers two concurrent registrations that both passed the lookup.
            if (repository.existsByEmail(email)) {
                throw new DuplicateMemberEmailException();
            }
            throw exception;
        }
    }
}
