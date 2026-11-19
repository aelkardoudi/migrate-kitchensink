package io.kitchensink.backfill.config;

import org.springframework.batch.core.job.parameters.DefaultJobParametersValidator;
import org.springframework.batch.core.job.parameters.InvalidJobParametersException;
import org.springframework.batch.core.job.parameters.JobParameters;

final class MemberBackfillJobParameters extends DefaultJobParametersValidator {
    static final String MAX_MEMBER_ID = "maxMemberId";
    private static final String MAX_MEMBER_ID_RANGE = "maxMemberId must be a decimal integer from 0 to 9223372036854775807";

    MemberBackfillJobParameters() {
        super(new String[]{"run.id"}, new String[0]);
    }

    @Override
    public void validate(JobParameters parameters) throws InvalidJobParametersException {
        super.validate(parameters);
        configuredMaxMemberId(parameters);
    }

    static Long configuredMaxMemberId(JobParameters parameters) throws InvalidJobParametersException {
        var parameter = parameters.getParameter(MAX_MEMBER_ID);
        if (parameter == null) {
            return null;
        }
        if (!parameter.identifying()) {
            throw new InvalidJobParametersException("maxMemberId must identify the Spring Batch job instance");
        }

        String value = String.valueOf(parameter.value());
        if (!value.matches("0|[1-9][0-9]{0,18}")) {
            throw new InvalidJobParametersException(MAX_MEMBER_ID_RANGE);
        }
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException exception) {
            throw new InvalidJobParametersException(MAX_MEMBER_ID_RANGE);
        }
    }
}
