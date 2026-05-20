import 'package:flutter_test/flutter_test.dart';

import 'package:notif_capture/main.dart';

void main() {
  test('detects claimed records from server claim fields', () {
    final record = NotificationCaptureRecord.fromMap({
      'documentId': 'doc-1',
      'timestampEpochMs': 1710000000000,
      'claimed_by_cid': 'server-cid',
    });

    expect(record.isClaimed, isTrue);
  });

  test('detects claimed_at snake_case records', () {
    final record = NotificationCaptureRecord.fromMap({
      'documentId': 'doc-2',
      'timestampEpochMs': 1710000000000,
      'claimed_at': '2026-05-20T09:15:00Z',
    });

    expect(record.isClaimed, isTrue);
  });

  test('does not mark unclaimed records as claimed', () {
    final record = NotificationCaptureRecord.fromMap({
      'documentId': 'doc-3',
      'timestampEpochMs': 1710000000000,
      'amount': 'P100.00',
    });

    expect(record.isClaimed, isFalse);
  });
}
