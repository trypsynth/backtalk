/* Copyright 2026 The Backtalk Authors. Licensed under the Apache License, Version 2.0. */
/* Exercise the actual driver with a fake wire and alarm service. Link with
 * function/data sections and --gc-sections to omit unrelated driver entry points. */
#define DRIVER_NAME FreedomScientific
#define DRIVER_CODE fs
#define DRIVER_COMMENT "test"
#define DRIVER_VERSION "test"
#define DRIVER_DEVELOPERS "test"
#include "../third_party/brltty/Drivers/Braille/FreedomScientific/braille.c"
#include <assert.h>

static FS_Packet sent;
static size_t sentSize;
static int writes;
static int writeOk = 1;

int writeBraillePacket(BrailleDisplay *brl, GioEndpoint *endpoint,
                       const void *packet, size_t size) {
  memcpy(&sent, packet, size);
  sentSize = size;
  writes++;
  return writeOk;
}

void *translateOutputCells(unsigned char *target, const unsigned char *source,
                           size_t count) {
  return memcpy(target, source, count);
}

int asyncNewRelativeAlarm(AsyncHandle *handle, int milliseconds,
                         AsyncAlarmCallback *callback, void *data) {
  assert(milliseconds == 500);
  *handle = (AsyncHandle)1;
  return 1;
}
void asyncDiscardHandle(AsyncHandle handle) {}
int logMessage(int level, const char *format, ...) { return 1; }

int main(void) {
  ModelEntry model = {.cellCount = 40};
  BrailleData data = {.model = &model, .writeFirst = -1, .writeLast = -1,
                     .firmnessSetting = -1, .outputPayloadLimit = 255,
                     .oldKeys = 1234};
  BrailleDisplay display = {.data = &data};
  for (int i = 0; i < 40; i++) data.outputBuffer[i] = i;

  assert(refreshDisplay(&display));
  assert(writes == 1 && sent.header.type == FS_PKT_WRITE);
  assert(sent.header.arg1 == 40 && sent.header.arg2 == 0);
  assert(sentSize == sizeof(FS_PacketHeader) + 41);
  assert(memcmp(sent.payload.bytes, data.outputBuffer, 40) == 0);
  unsigned char checksum = 0;
  for (size_t i = 0; i < sentSize; i++) checksum += ((unsigned char *)&sent)[i];
  assert(checksum == 0);
  assert(data.acknowledgementHandler == handleWriteAcknowledgement);
  assert(data.oldKeys == 1234 && data.firmnessSetting == -1);

  // Refresh must queue behind the existing ACK, not send an overlapping packet.
  assert(refreshDisplay(&display));
  assert(writes == 1);
  data.outputBuffer[10] = 99; // New content arrives while the first write is pending.
  assert(handleAcknowledgement(&display, 1));
  assert(writes == 2 && sent.payload.bytes[10] == 99);
  assert(handleAcknowledgement(&display, 1));
  assert(writes == 2); // No endless self-renewing refresh loop.

  // Respect the driver's reduced payload size and ACK each chunk.
  data.outputPayloadLimit = 20;
  assert(refreshDisplay(&display));
  assert(writes == 3 && sent.header.arg1 == 20 && sent.header.arg2 == 0);
  assert(handleAcknowledgement(&display, 1));
  assert(writes == 4 && sent.header.arg1 == 20 && sent.header.arg2 == 20);
  assert(handleAcknowledgement(&display, 1));
  assert(writes == 4);

  writeOk = 0;
  assert(!refreshDisplay(&display));
  puts("focus_refresh_test: passed");
  return 0;
}
