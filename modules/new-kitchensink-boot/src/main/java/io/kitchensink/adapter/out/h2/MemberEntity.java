package io.kitchensink.adapter.out.h2;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "member", uniqueConstraints = @UniqueConstraint(name = "member_email_unique", columnNames = "email"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MemberEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "member-sequence")
    @SequenceGenerator(name = "member-sequence", sequenceName = "Member_SEQ", allocationSize = 50)
    private Long id;

    @NotNull
    @Size(min = 1, max = 25)
    @Pattern(regexp = "[^0-9]*", message = "Must not contain numbers")
    private String name;

    @NotNull
    @NotEmpty
    @Email
    private String email;

    @NotNull
    @Size(min = 10, max = 12)
    @Digits(integer = 12, fraction = 0)
    @Column(name = "phone_number")
    private String phoneNumber;

    MemberEntity(String name, String email, String phoneNumber) {
        this.name = name;
        this.email = email;
        this.phoneNumber = phoneNumber;
    }
}
