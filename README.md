# NetworkCompatible

## Introduction

You can join the [Discord](https://discord.gg/5z4GuSnqmQ) for help with this fork. This is a fork of [CloudburstMC/Network](https://github.com/CloudburstMC/Network) with a focus on improving the compatibility of the client side of the library to more closely align with the vanilla Minecraft Bedrock client.

## Changes from Original Library

- New incoming connection batches additional packets to more closely imitate the vanilla client:
  - A `Connected Ping`
  - The first game packet, `Request Network Settings Packet`
- Allows for resetting security state if `Open Connection Reply 1` is resent by the server
- Only do retries with `Open Connection Request 1`, and reserve `Open Connection Request 2` only as a direct response to `Open Connection Reply 1`
- Allows using datagram channel factories for raknet (from [@AlexProgrammerDE](https://github.com/AlexProgrammerDE))
- Skips over improperly typed client address fields

## Usage

### Releases ![Maven Central Version](https://img.shields.io/maven-central/v/dev.kastle.netty/netty-transport-raknet?label=Maven%20Central&color=%233fb950)

The library is published to Maven Central. See the [latest release](https://github.com/Kas-tle/NetworkCompatible/releases/latest) for the latest version.

### Snapshots [![](https://jitpack.io/v/dev.kastle/NetworkCompatible.svg)](https://jitpack.io/#dev.kastle/NetworkCompatible)

Snapshots are avaible from [jitpack](https://jitpack.io/#dev.kastle/NetworkCompatible).
