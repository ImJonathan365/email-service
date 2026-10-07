package com.emailservice.common.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class CidrTest {

	@Test
	void ipv4NetworkContainsOnlyItsAddresses() {
		Cidr cidr = Cidr.parse("10.20.0.0/16");
		assertThat(cidr.contains(Cidr.literal("10.20.255.1"))).isTrue();
		assertThat(cidr.contains(Cidr.literal("10.21.0.1"))).isFalse();
		assertThat(cidr.contains(Cidr.literal("::ffff:0a14:0001"))).isTrue();
		assertThat(cidr.contains(Cidr.literal("2001:db8::1"))).isFalse();
	}

	@Test
	void ipv6NetworkContainsOnlyItsAddresses() {
		Cidr cidr = Cidr.parse("2001:db8::/32");
		assertThat(cidr.contains(Cidr.literal("2001:db8:ffff::1"))).isTrue();
		assertThat(cidr.contains(Cidr.literal("2001:db9::1"))).isFalse();
		assertThat(cidr.contains(Cidr.literal("10.0.0.1"))).isFalse();
	}

	@Test
	void bareAddressIsASingleHostAndPrefixZeroMatchesAll() {
		assertThat(Cidr.parse("192.168.1.10").toString()).isEqualTo("192.168.1.10/32");
		assertThat(Cidr.parse("0.0.0.0/0").contains(Cidr.literal("8.8.8.8"))).isTrue();
		assertThat(Cidr.parse("10.0.0.0/9").contains(Cidr.literal("10.127.0.1"))).isTrue();
		assertThat(Cidr.parse("10.0.0.0/9").contains(Cidr.literal("10.128.0.1"))).isFalse();
	}

	@Test
	void rejectsHostBitsHostNamesAndBadPrefixes() {
		assertThatThrownBy(() -> Cidr.parse("10.20.0.1/16")).hasMessageContaining("bits set");
		assertThatThrownBy(() -> Cidr.parse("localhost")).hasMessageContaining("Invalid IP");
		assertThatThrownBy(() -> Cidr.parse("10.0.0.0/33")).hasMessageContaining("prefix");
		assertThatThrownBy(() -> Cidr.parse("10.0.0.0/x")).hasMessageContaining("prefix");
		assertThatThrownBy(() -> Cidr.parse(" ")).hasMessageContaining("empty");
	}

}
