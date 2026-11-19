package io.kitchensink.adapter.out.mongo;

import java.util.List;

import org.springframework.context.annotation.Profile;
import org.springframework.data.mongodb.repository.MongoRepository;

@Profile("mongo")
public interface MongoMemberRepository extends MongoRepository<MemberDocument, Long> {
    List<MemberDocument> findAllByOrderByNameAsc();

    boolean existsByEmail(String email);
}
