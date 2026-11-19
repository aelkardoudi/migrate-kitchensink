package io.kitchensink.backfill;

import io.kitchensink.backfill.verify.MemberBackfillVerification;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class MemberBackfillApplication {
    public static void main(String[] args) {
        int exitCode;
        if (args.length > 0 && "verify".equals(args[0])) {
            exitCode = MemberBackfillVerification.run(args);
        } else {
            exitCode = SpringApplication.exit(SpringApplication.run(MemberBackfillApplication.class, args));
        }
        System.exit(exitCode);
    }
}
