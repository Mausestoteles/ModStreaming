package dev.modstreaming.gradle.fixture.secret;

import dev.modstreaming.api.Streamed;
import dev.modstreaming.gradle.fixture.PublicService;

import java.util.function.Supplier;

@Streamed
public class SecretImpl implements PublicService {

    public static final class Nested {
        public String value() {
            return "nested";
        }
    }

    private final class InnerMember {
        String value() {
            return "inner";
        }
    }

    @Override
    public String compute(String input) {
        Supplier<String> anonymous = new Supplier<>() {
            @Override
            public String get() {
                class Local {
                    String v() {
                        return "local";
                    }
                }
                return new Local().v() + new InnerMember().value();
            }
        };
        Supplier<String> lambda = () -> new Nested().value();
        return input + ":" + anonymous.get() + ":" + lambda.get();
    }
}
