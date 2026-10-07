package com.spydrone.orthanc_scan_producer.stage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.spydrone.orthanc_scan_producer.stage.StageCatalog.Stage;

class StageCatalogTest {

	private static final String JSON = """
			{"photolithography":{"description":"Photolithography","next-stages":["wet-etching"],
			  "wip-locations":{"PHOTO-001":{"description":"Photo 1"}},
			  "next-wip-locations":{"wet-etching":["WET-001"]}},
			 "wet-etching":{"description":"Wet Etching","next-stages":[],"wip-locations":{},"extra":true}}
			""";
	private static final Instant START = Instant.parse("2026-10-07T12:00:00Z");

	private final LotStageClient client = mock(LotStageClient.class);
	private final MutableClock clock = new MutableClock();
	private final StageCatalog catalog = new StageCatalog(client, clock);

	@Test
	void parsesTheCatalog() {
		given(client.stages()).willReturn(Optional.of(JSON));

		Map<String, Stage> stages = catalog.stages().orElseThrow();

		Stage photo = stages.get("photolithography");
		assertThat(photo.description()).isEqualTo("Photolithography");
		assertThat(photo.nextStages()).containsExactly("wet-etching");
		assertThat(photo.wipLocations()).containsOnlyKeys("PHOTO-001");
		assertThat(photo.nextWipLocations()).containsEntry("wet-etching", List.of("WET-001"));
		assertThat(stages.get("wet-etching").nextWipLocations()).isEmpty();
	}

	@Test
	void keepsTheCatalogForAMinute() {
		given(client.stages()).willReturn(Optional.of(JSON));

		catalog.stages();
		clock.advance(Duration.ofSeconds(59));
		catalog.stages();
		verify(client, times(1)).stages();

		clock.advance(Duration.ofSeconds(1));
		catalog.stages();
		verify(client, times(2)).stages();
	}

	@Test
	void keepsTheLastCatalogWhenANewOneCantBeRead() {
		given(client.stages()).willReturn(Optional.of(JSON)).willReturn(Optional.of("not json"));

		catalog.stages();
		clock.advance(Duration.ofMinutes(2));

		assertThat(catalog.stages().orElseThrow()).containsKey("photolithography");
	}

	@Test
	void emptyWithoutACatalog() {
		given(client.stages()).willReturn(Optional.empty());

		assertThat(catalog.stages()).isEmpty();
	}

	private static final class MutableClock extends Clock {

		private Instant now = START;

		void advance(Duration duration) {
			now = now.plus(duration);
		}

		@Override
		public Instant instant() {
			return now;
		}

		@Override
		public ZoneId getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(ZoneId zone) {
			return this;
		}
	}
}
