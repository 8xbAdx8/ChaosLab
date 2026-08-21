package com.chaoslab.target.application;

import com.chaoslab.target.application.dto.RegisterTargetCommand;
import com.chaoslab.target.application.dto.TargetDetails;
import com.chaoslab.target.application.port.TargetRepository;
import com.chaoslab.target.domain.Target;
import com.chaoslab.target.domain.TargetEnvironment;
import com.chaoslab.target.domain.TargetType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TargetApplicationServiceTests {

    @Mock
    private TargetRepository targetRepository;

    @InjectMocks
    private TargetApplicationService targetApplicationService;

    @Test
    void shouldRegisterTargetThroughRepository() {
        when(targetRepository.save(any(Target.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        TargetDetails result = targetApplicationService.register(new RegisterTargetCommand(
                "  demo-order-container  ",
                TargetType.DOCKER_CONTAINER,
                TargetEnvironment.CHAOS_LAB
        ));

        ArgumentCaptor<Target> targetCaptor = ArgumentCaptor.forClass(Target.class);
        verify(targetRepository).save(targetCaptor.capture());
        Target savedTarget = targetCaptor.getValue();

        assertThat(result.id()).isEqualTo(savedTarget.getId()).isNotNull();
        assertThat(result.name()).isEqualTo("demo-order-container");
        assertThat(result.type()).isEqualTo(TargetType.DOCKER_CONTAINER);
        assertThat(result.environment()).isEqualTo(TargetEnvironment.CHAOS_LAB);
        assertThat(result.enabled()).isTrue();
    }

    @Test
    void shouldFindTargetById() {
        UUID targetId = UUID.fromString("7bc6c002-a456-46f9-8d66-52eda53cb90f");
        Target target = Target.register(
                targetId,
                "demo-target",
                TargetType.LINUX_HOST,
                TargetEnvironment.TEST
        );
        when(targetRepository.findById(targetId)).thenReturn(Optional.of(target));

        TargetDetails result = targetApplicationService.findById(targetId);

        assertThat(result.id()).isEqualTo(targetId);
        assertThat(result.name()).isEqualTo("demo-target");
    }

    @Test
    void shouldFailWhenTargetDoesNotExist() {
        UUID targetId = UUID.fromString("454e135a-4d97-4df7-8ea9-c61eaa4937de");
        when(targetRepository.findById(targetId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> targetApplicationService.findById(targetId))
                .isInstanceOf(TargetNotFoundException.class)
                .hasMessage("target not found: " + targetId);
    }

    @Test
    void shouldReturnTargetDetailsWithoutExposingEntities() {
        Target first = Target.register(
                UUID.fromString("afc3f86f-f255-4057-90a3-5dc224d8101a"),
                "first-target",
                TargetType.DOCKER_CONTAINER,
                TargetEnvironment.LOCAL
        );
        Target second = Target.register(
                UUID.fromString("9e15d1fb-adc9-417a-89f2-ece83f78ec14"),
                "second-target",
                TargetType.JAVA_APPLICATION,
                TargetEnvironment.DEV
        );
        when(targetRepository.findAll()).thenReturn(List.of(first, second));

        List<TargetDetails> result = targetApplicationService.findAll();

        assertThat(result)
                .extracting(TargetDetails::name)
                .containsExactly("first-target", "second-target");
    }
}
