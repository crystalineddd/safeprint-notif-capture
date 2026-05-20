import 'package:cloud_firestore/cloud_firestore.dart';
import 'package:firebase_core/firebase_core.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:google_fonts/google_fonts.dart';

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  await Firebase.initializeApp();

  runApp(const MyApp());
}

class NotificationCaptureRecord {
  const NotificationCaptureRecord({
    required this.documentId,
    required this.timestamp,
    required this.amount,
    required this.number,
    required this.isParsed,
    required this.isUploadedToFirebase,
    required this.isClaimed,
    required this.rawText,
    required this.packageName,
    required this.title,
    required this.isGcashSource,
    required this.parseCategory,
    required this.parseHint,
  });

  final String documentId;
  final DateTime timestamp;
  final String amount;
  final String number;
  final bool isParsed;
  final bool isUploadedToFirebase;
  final bool isClaimed;
  final String rawText;
  final String packageName;
  final String title;
  final bool isGcashSource;
  final String parseCategory;
  final String parseHint;

  String get amountLabel => amount.isEmpty ? '(not parsed)' : amount;

  String get numberLabel => number.isEmpty ? '(not parsed)' : number;

  String get parseStatusLabel {
    switch (parseCategory) {
      case 'payment':
        return 'Parsed payment';
      case 'promo_notification':
        return 'Promo/advisory';
      case 'missing_amount':
        return 'Missing amount';
      case 'missing_number':
        return 'Missing number';
      case 'missing_amount_and_number':
        return 'Missing amount and number';
      default:
        return isParsed ? 'Parsed payment' : 'Needs review';
    }
  }

  String get parseHintLabel {
    if (parseHint.isNotEmpty) {
      return parseHint;
    }

    if (isParsed) {
      return 'Parsed as a GCash payment receipt.';
    }

    return 'Captured a GCash notification, but the payment parser could not classify it.';
  }

  bool get isPromoLike => parseCategory == 'promo_notification';

  bool get isFromGcashApp {
    return packageName == 'com.globe.gcash.android' ||
        packageName == 'com.globe.gcash';
  }

  bool get isLikelyGcashRecord {
    if (isFromGcashApp || isGcashSource) {
      return true;
    }

    final normalizedText = rawText.toLowerCase();
    final normalizedTitle = title.toLowerCase();
    return normalizedText.contains('gcash') || normalizedTitle.contains('gcash');
  }

  bool get hasCapturedPaymentDetails {
    return amount.trim().isNotEmpty && number.trim().isNotEmpty;
  }

  String get derivedName {
    final text = rawText.replaceAll('\n', ' ');
    final match = RegExp(
      // Keep masked names exactly as-is (e.g. ME**Y C.).
      r'from\s+(.+?)\s+(?:\+63|09)[0-9\-\s*]{8,}',
      caseSensitive: false,
    ).firstMatch(text);

    if (match != null) {
      return match.group(1)?.trim() ?? '(unknown)';
    }

    if (title.isNotEmpty) {
      return title;
    }

    return '(unknown)';
  }

  static bool _isClaimed(Map<dynamic, dynamic> map) {
    return
        (map['isClaimed'] as bool?) ??
        (map['claimed'] as bool?) ??
        (map['is_claimed'] as bool?) ??
        ((map['claimed_by_cid']?.toString().trim().isNotEmpty ?? false) ||
            (map['claimed_by_intent_id']?.toString().trim().isNotEmpty ??
                false) ||
            map['claimed_at'] != null ||
            map['claimedAt'] != null);
  }

  factory NotificationCaptureRecord.fromMap(
    Map<dynamic, dynamic> map, {
    String? documentId,
    bool isUploadedToFirebase = false,
  }) {
    final capturedAt = map['capturedAt'];
    final capturedAtMillis = capturedAt is Timestamp
        ? capturedAt.millisecondsSinceEpoch
        : null;
    final millis =
        (map['timestampEpochMs'] as num?)?.toInt() ??
        capturedAtMillis ??
        DateTime.now().millisecondsSinceEpoch;

    return NotificationCaptureRecord(
      documentId: documentId ?? (map['documentId']?.toString() ?? ''),
      timestamp: DateTime.fromMillisecondsSinceEpoch(millis),
      amount: (map['amount']?.toString() ?? '').trim(),
      number: (map['number']?.toString() ?? '').trim(),
      isParsed: (map['isParsed'] as bool?) ?? false,
      isUploadedToFirebase: isUploadedToFirebase,
      isClaimed: _isClaimed(map),
      rawText: (map['rawText']?.toString() ?? '').trim(),
      packageName: (map['packageName']?.toString() ?? '').trim(),
      title: (map['title']?.toString() ?? '').trim(),
      isGcashSource: (map['isGcashSource'] as bool?) ?? false,
      parseCategory: (map['parseCategory']?.toString() ?? 'payment').trim(),
      parseHint: (map['parseHint']?.toString() ?? '').trim(),
    );
  }
}

class NotificationBridge {
  NotificationBridge._();

  static const MethodChannel _methodChannel = MethodChannel(
    'gcash_capture/methods',
  );
  static Future<void> openNotificationAccessSettings() {
    return _methodChannel.invokeMethod('openNotificationAccessSettings');
  }

  static Future<bool> isNotificationAccessGranted() async {
    final granted = await _methodChannel.invokeMethod<bool>(
      'isNotificationAccessGranted',
    );
    return granted ?? false;
  }

  static Future<bool> isAppNotificationPermissionGranted() async {
    final granted = await _methodChannel.invokeMethod<bool>(
      'isAppNotificationPermissionGranted',
    );
    return granted ?? true;
  }

  static Future<bool> requestAppNotificationPermission() async {
    final granted = await _methodChannel.invokeMethod<bool>(
      'requestAppNotificationPermission',
    );
    return granted ?? false;
  }

  static Future<List<NotificationCaptureRecord>>
  loadSavedNotifications() async {
    try {
      final saved = await _methodChannel.invokeMethod<List>(
        'loadSavedNotifications',
      );
      if (saved != null) {
        return (saved).map((item) {
          final map = Map<String, dynamic>.from(item as Map);
          return NotificationCaptureRecord.fromMap(map);
        }).toList();
      }
    } catch (_) {
      // If method not available yet, return empty
    }
    return [];
  }

  static Future<bool> isCaptureEnabled() async {
    final enabled = await _methodChannel.invokeMethod<bool>('isCaptureEnabled');
    return enabled ?? true;
  }

  static Future<void> setCaptureEnabled(bool enabled) {
    return _methodChannel.invokeMethod('setCaptureEnabled', {
      'enabled': enabled,
    });
  }

  static Future<void> syncSavedNotificationsToFirebase() {
    return _methodChannel.invokeMethod('syncSavedNotificationsToFirebase');
  }
}

class FirebaseCaptureRepository {
  FirebaseCaptureRepository._();

  static final FirebaseFirestore _db = FirebaseFirestore.instance;

  static Stream<List<NotificationCaptureRecord>> stream() {
    return _db
        .collection('gcash_notifications')
        .orderBy('capturedAt', descending: true)
        .limit(100)
        .snapshots()
        .map((snapshot) {
          final records = snapshot.docs
              .map(
                (doc) => NotificationCaptureRecord.fromMap(
                  doc.data(),
                  documentId: doc.id,
                  isUploadedToFirebase: true,
                ),
              )
              .toList();
          records.sort(
            (left, right) => right.timestamp.compareTo(left.timestamp),
          );
          return records;
        });
  }
}

class MyApp extends StatelessWidget {
  const MyApp({super.key});

  static const Color brandColor = Color(0xFFFBB41D);

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'SafePrint',
      debugShowCheckedModeBanner: false,
      theme: ThemeData(
        textTheme: GoogleFonts.spaceGroteskTextTheme(),
        colorScheme: ColorScheme.fromSeed(seedColor: brandColor),
        scaffoldBackgroundColor: const Color(0xFFF7F9FC),
        appBarTheme: const AppBarTheme(
          backgroundColor: Colors.white,
          foregroundColor: Color(0xFF1F2937),
          elevation: 0,
          centerTitle: false,
        ),
      ),
      home: const CapturePage(),
    );
  }
}

class CapturePage extends StatefulWidget {
  const CapturePage({super.key});

  @override
  State<CapturePage> createState() => _CapturePageState();
}

class _CapturePageState extends State<CapturePage> with WidgetsBindingObserver {
  static const double _rowFontSize = 10;
  static const List<String> _tabs = [
    'All Notification',
    'All Parsed',
    'Not Parsed GCash Notifs',
  ];

  List<NotificationCaptureRecord> _savedLocalRecords =
      <NotificationCaptureRecord>[];
  bool _hasNotificationAccess = false;
  bool _hasAppNotificationPermission = true;
  bool _isCaptureEnabled = true;
  bool _isSyncing = false;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _init();
  }

  Future<void> _init() async {
    await _refreshAccessStatus();
    await _refreshSavedNotifications();
    await _syncSavedNotifications();
  }

  Future<void> _refreshSavedNotifications() async {
    final saved = await NotificationBridge.loadSavedNotifications();
    if (!mounted) {
      return;
    }

    setState(() {
      _savedLocalRecords = saved.where((record) => record.isFromGcashApp).toList();
    });
  }

  Future<void> _syncSavedNotifications() async {
    if (_isSyncing) {
      return;
    }

    _isSyncing = true;
    try {
      await NotificationBridge.syncSavedNotificationsToFirebase();
    } catch (_) {
      // Keep the UI usable even when cloud sync is temporarily unavailable.
    } finally {
      _isSyncing = false;
      await _refreshSavedNotifications();
    }
  }

  Future<void> _refreshAccessStatus() async {
    final granted = await NotificationBridge.isNotificationAccessGranted();
    final appNotificationPermission =
        await NotificationBridge.isAppNotificationPermissionGranted();
    final captureEnabled = await NotificationBridge.isCaptureEnabled();
    if (!mounted) {
      return;
    }

    setState(() {
      _hasNotificationAccess = granted;
      _hasAppNotificationPermission = appNotificationPermission;
      _isCaptureEnabled = captureEnabled;
    });
  }

  Future<void> _requestAppNotificationPermission() async {
    await NotificationBridge.requestAppNotificationPermission();
    await _refreshAccessStatus();
  }

  Future<void> _setCaptureEnabled(bool enabled) async {
    await NotificationBridge.setCaptureEnabled(enabled);
    await _refreshAccessStatus();
    if (enabled) {
      await _syncSavedNotifications();
    }
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) {
      _refreshAccessStatus();
      _refreshSavedNotifications();
      _syncSavedNotifications();
    }
  }

  String _formatTimestamp(DateTime value) {
    final month = value.month.toString().padLeft(2, '0');
    final day = value.day.toString().padLeft(2, '0');
    final year = (value.year % 100).toString().padLeft(2, '0');

    final hour12 = value.hour == 0
        ? 12
        : value.hour > 12
        ? value.hour - 12
        : value.hour;
    final hour = hour12.toString().padLeft(2, '0');
    final minute = value.minute.toString().padLeft(2, '0');
    final suffix = value.hour >= 12 ? 'PM' : 'AM';

    return '$month-$day-$year $hour:$minute $suffix';
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  String get _statusMessage {
    if (!_hasNotificationAccess) {
      return 'Notification access is not enabled yet. Tap Open Access Settings and turn SafePrint on.';
    }

    if (!_hasAppNotificationPermission) {
      return 'Android app notifications are off. Enable them so the pinned “SafePrint is listening” notification stays visible in the notification shade.';
    }

    if (!_isCaptureEnabled) {
      return 'Notification access is enabled, but background listening is paused. Resume it to show the pinned listener notification again.';
    }

    return 'Notification access is enabled. SafePrint keeps a pinned Android notification while it listens and uploads captures to the server.';
  }

  Color get _statusBackgroundColor {
    if (!_hasNotificationAccess) {
      return const Color(0xFFFFF6E0);
    }

    if (!_hasAppNotificationPermission) {
      return const Color(0xFFEFF6FF);
    }

    return _isCaptureEnabled
        ? const Color(0xFFEFFAF3)
        : const Color(0xFFFFF3F0);
  }

  Color get _statusBorderColor {
    if (!_hasNotificationAccess) {
      return const Color(0xFFF4C152);
    }

    if (!_hasAppNotificationPermission) {
      return const Color(0xFF93C5FD);
    }

    return _isCaptureEnabled
        ? const Color(0xFFB7E4C7)
        : const Color(0xFFF3B5A7);
  }

  Widget _buildActionButtons() {
    return Wrap(
      spacing: 10,
      runSpacing: 10,
      children: [
        SizedBox(
          width: 170,
          child: ElevatedButton(
            onPressed: () async {
              await NotificationBridge.openNotificationAccessSettings();
            },
            style: ElevatedButton.styleFrom(
              backgroundColor: const Color(0xFFFBB41D),
              foregroundColor: const Color(0xFF1F2937),
              padding: const EdgeInsets.symmetric(vertical: 10),
              shape: RoundedRectangleBorder(
                borderRadius: BorderRadius.circular(20),
              ),
              elevation: 0,
            ),
            child: const Text(
              'Open Access Settings',
              style: TextStyle(fontWeight: FontWeight.w700, fontSize: 12),
            ),
          ),
        ),
        if (_hasNotificationAccess && !_hasAppNotificationPermission)
          SizedBox(
            width: 170,
            child: OutlinedButton(
              onPressed: _requestAppNotificationPermission,
              style: OutlinedButton.styleFrom(
                foregroundColor: const Color(0xFF1F2937),
                side: const BorderSide(color: Color(0xFF93C5FD)),
                padding: const EdgeInsets.symmetric(vertical: 10),
                shape: RoundedRectangleBorder(
                  borderRadius: BorderRadius.circular(20),
                ),
              ),
              child: const Text(
                'Enable App Notifications',
                style: TextStyle(fontWeight: FontWeight.w700, fontSize: 12),
              ),
            ),
          ),
        if (_hasNotificationAccess)
          SizedBox(
            width: 140,
            child: OutlinedButton(
              onPressed: () async {
                await _setCaptureEnabled(!_isCaptureEnabled);
              },
              style: OutlinedButton.styleFrom(
                foregroundColor: const Color(0xFF1F2937),
                side: BorderSide(
                  color: _isCaptureEnabled
                      ? const Color(0xFFF3B5A7)
                      : const Color(0xFFB7E4C7),
                ),
                padding: const EdgeInsets.symmetric(vertical: 10),
                shape: RoundedRectangleBorder(
                  borderRadius: BorderRadius.circular(20),
                ),
              ),
              child: Text(
                _isCaptureEnabled ? 'Pause Listener' : 'Resume Listener',
                style: const TextStyle(
                  fontWeight: FontWeight.w700,
                  fontSize: 12,
                ),
              ),
            ),
          ),
      ],
    );
  }

  Widget _buildRecordTile(NotificationCaptureRecord item) {
    return Container(
      decoration: BoxDecoration(
        color: Colors.white,
        borderRadius: BorderRadius.circular(14),
        border: Border.all(color: const Color(0xFFE5E7EB)),
      ),
      child: ExpansionTile(
        tilePadding: const EdgeInsets.symmetric(horizontal: 14, vertical: 4),
        childrenPadding: const EdgeInsets.fromLTRB(14, 0, 14, 14),
        iconColor: const Color(0xFF2563EB),
        collapsedIconColor: const Color(0xFF2563EB),
        title: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
              _formatTimestamp(item.timestamp),
              overflow: TextOverflow.ellipsis,
              style: const TextStyle(
                fontSize: _rowFontSize,
                fontWeight: FontWeight.w700,
                color: Color(0xFF111827),
              ),
            ),
            const SizedBox(height: 6),
            Wrap(
              spacing: 6,
              runSpacing: 6,
              children: [
                _ParseBadge(record: item),
                _ClaimBadge(isClaimed: item.isClaimed),
              ],
            ),
          ],
        ),
        subtitle: Padding(
          padding: const EdgeInsets.only(top: 6),
          child: Text(
            item.amount.isEmpty ? item.parseHintLabel : item.amount,
            maxLines: 2,
            overflow: TextOverflow.ellipsis,
            style: const TextStyle(
              fontSize: 11,
              color: Color(0xFF4B5563),
              height: 1.25,
            ),
          ),
        ),
        children: [
          _DetailRow(
            label: 'Name',
            value: item.derivedName,
            fontSize: _rowFontSize,
          ),
          const SizedBox(height: 8),
          _DetailRow(
            label: 'Amount',
            value: item.amountLabel,
            fontSize: _rowFontSize,
          ),
          const SizedBox(height: 8),
          _DetailRow(
            label: 'Contact Number',
            value: item.numberLabel,
            fontSize: _rowFontSize,
          ),
          const SizedBox(height: 8),
          _DetailRow(
            label: 'Parse Status',
            value: item.parseStatusLabel,
            fontSize: _rowFontSize,
          ),
          const SizedBox(height: 8),
          _DetailRow(
            label: 'Parse Hint',
            value: item.parseHintLabel,
            fontSize: _rowFontSize,
          ),
          const SizedBox(height: 8),
          _DetailRow(
            label: 'Firebase Upload',
            value: item.isUploadedToFirebase ? 'Uploaded' : 'Not uploaded',
            fontSize: _rowFontSize,
          ),
          const SizedBox(height: 8),
          _DetailRow(
            label: 'Claim Status',
            value: item.isClaimed ? 'Claimed' : 'Not claimed',
            fontSize: _rowFontSize,
          ),
          const SizedBox(height: 8),
          if (!item.isParsed) ...[
            _DetailRow(
              label: 'Notification Title',
              value: item.title.isEmpty ? '(untitled)' : item.title,
              fontSize: _rowFontSize,
            ),
            const SizedBox(height: 8),
            _DetailRow(
              label: 'Source App',
              value: item.packageName,
              fontSize: _rowFontSize,
            ),
            const SizedBox(height: 8),
          ],
          _DetailRow(
            label: 'Raw Text',
            value: item.rawText,
            fontSize: _rowFontSize,
          ),
        ],
      ),
    );
  }

  List<NotificationCaptureRecord> _filterRecords(
    List<NotificationCaptureRecord> records,
    int tabIndex,
  ) {
    switch (tabIndex) {
      case 1:
        return records
            .where(
              (record) =>
                  record.isLikelyGcashRecord &&
                  record.hasCapturedPaymentDetails,
            )
            .toList();
      case 2:
        return records
            .where(
              (record) =>
                  record.isLikelyGcashRecord &&
                  !record.hasCapturedPaymentDetails,
            )
            .toList();
      default:
        return records.where((record) => record.isLikelyGcashRecord).toList();
    }
  }

  List<NotificationCaptureRecord> _mergeRecords(
    List<NotificationCaptureRecord> firebaseRecords,
  ) {
    final mergedById = <String, NotificationCaptureRecord>{};

    for (final record in _savedLocalRecords) {
      final key = record.documentId.isNotEmpty
          ? record.documentId
          : '${record.packageName}_${record.timestamp.millisecondsSinceEpoch}';
      mergedById[key] = record;
    }

    for (final record in firebaseRecords) {
      final key = record.documentId.isNotEmpty
          ? record.documentId
          : '${record.packageName}_${record.timestamp.millisecondsSinceEpoch}';
      mergedById[key] = record;
    }

    final merged = mergedById.values.toList()
      ..sort((left, right) => right.timestamp.compareTo(left.timestamp));
    return merged.take(100).toList();
  }

  Widget _buildRecordsList(
    List<NotificationCaptureRecord> records,
    int tabIndex,
  ) {
    final filteredRecords = _filterRecords(records, tabIndex);

    if (filteredRecords.isEmpty) {
      return Center(child: Text('No ${_tabs[tabIndex].toLowerCase()} yet.'));
    }

    return ListView.separated(
      itemCount: filteredRecords.length,
      separatorBuilder: (_, _) => const SizedBox(height: 10),
      itemBuilder: (context, index) {
        return _buildRecordTile(filteredRecords[index]);
      },
    );
  }

  @override
  Widget build(BuildContext context) {
    return DefaultTabController(
      length: _tabs.length,
      child: Scaffold(
        appBar: AppBar(
          title: Row(
            children: [
              Image.asset('assets/icon/app_icon.png', width: 24, height: 24),
              const SizedBox(width: 8),
              Text(
                'SafePrint Payments',
                style: GoogleFonts.spaceGrotesk(
                  fontSize: 18,
                  fontWeight: FontWeight.w700,
                  color: const Color(0xFF1F2937),
                ),
              ),
            ],
          ),
        ),
        body: Padding(
          padding: const EdgeInsets.all(16),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Container(
                width: double.infinity,
                margin: const EdgeInsets.only(bottom: 12),
                padding: const EdgeInsets.all(12),
                decoration: BoxDecoration(
                  color: _statusBackgroundColor,
                  borderRadius: BorderRadius.circular(14),
                  border: Border.all(color: _statusBorderColor),
                ),
                child: Text(
                  _statusMessage,
                  style: const TextStyle(
                    fontSize: 12,
                    height: 1.3,
                    color: Color(0xFF1F2937),
                    fontWeight: FontWeight.w600,
                  ),
                ),
              ),
              const Text(
                'Showing the last 100 Firebase records plus pending local captures, including claimed status.',
                style: TextStyle(
                  fontSize: 11,
                  fontWeight: FontWeight.w600,
                  color: Color(0xFF4B5563),
                ),
              ),
              const SizedBox(height: 12),
              _buildActionButtons(),
              const SizedBox(height: 16),
              Container(
                height: 44,
                decoration: BoxDecoration(
                  color: const Color(0xFFEFF3F8),
                  borderRadius: BorderRadius.circular(8),
                ),
                child: TabBar(
                  indicatorSize: TabBarIndicatorSize.tab,
                  dividerColor: Colors.transparent,
                  indicator: BoxDecoration(
                    color: Colors.white,
                    borderRadius: BorderRadius.circular(8),
                    border: Border.all(color: const Color(0xFFE5E7EB)),
                  ),
                  labelColor: const Color(0xFF111827),
                  unselectedLabelColor: const Color(0xFF6B7280),
                  labelStyle: const TextStyle(
                    fontSize: 9,
                    fontWeight: FontWeight.w700,
                  ),
                  tabs: const [
                    Tab(
                      child: FittedBox(
                        fit: BoxFit.scaleDown,
                        child: Text('All Notification'),
                      ),
                    ),
                    Tab(
                      child: FittedBox(
                        fit: BoxFit.scaleDown,
                        child: Text('All Parsed'),
                      ),
                    ),
                    Tab(
                      child: FittedBox(
                        fit: BoxFit.scaleDown,
                        child: Text('Not Parsed GCash'),
                      ),
                    ),
                  ],
                ),
              ),
              const SizedBox(height: 12),
              Expanded(
                child: StreamBuilder<List<NotificationCaptureRecord>>(
                  stream: FirebaseCaptureRepository.stream(),
                  builder: (context, snapshot) {
                    if (snapshot.hasError) {
                      return const Center(
                        child: Text(
                          'Unable to load Firebase records right now.',
                          style: TextStyle(color: Color(0xFFB91C1C)),
                        ),
                      );
                    }

                    if (!snapshot.hasData) {
                      return const Center(child: CircularProgressIndicator());
                    }

                    final records = _mergeRecords(snapshot.data!);
                    if (records.isEmpty) {
                      return const Center(
                        child: Text('No GCash captures yet.'),
                      );
                    }

                    return TabBarView(
                      children: [
                        _buildRecordsList(records, 0),
                        _buildRecordsList(records, 1),
                        _buildRecordsList(records, 2),
                      ],
                    );
                  },
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class _ParseBadge extends StatelessWidget {
  const _ParseBadge({required this.record});

  final NotificationCaptureRecord record;

  @override
  Widget build(BuildContext context) {
    final backgroundColor = record.isUploadedToFirebase
        ? const Color(0xFFDCFCE7)
        : const Color(0xFFE5E7EB);
    final foregroundColor = record.isUploadedToFirebase
        ? const Color(0xFF166534)
        : const Color(0xFF4B5563);

    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
      decoration: BoxDecoration(
        color: backgroundColor,
        borderRadius: BorderRadius.circular(999),
      ),
      child: Text(
        '${record.parseStatusLabel} · ${record.isUploadedToFirebase ? 'Uploaded' : 'Not uploaded'}',
        style: TextStyle(
          fontSize: 10,
          fontWeight: FontWeight.w700,
          color: foregroundColor,
        ),
      ),
    );
  }
}

class _ClaimBadge extends StatelessWidget {
  const _ClaimBadge({required this.isClaimed});

  final bool isClaimed;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
      decoration: BoxDecoration(
        color: isClaimed ? const Color(0xFFE0F2FE) : const Color(0xFFFFF7ED),
        borderRadius: BorderRadius.circular(999),
      ),
      child: Text(
        isClaimed ? 'Claimed' : 'Not claimed',
        style: TextStyle(
          fontSize: 10,
          fontWeight: FontWeight.w700,
          color: isClaimed ? const Color(0xFF075985) : const Color(0xFF9A3412),
        ),
      ),
    );
  }
}

class _DetailRow extends StatelessWidget {
  const _DetailRow({
    required this.label,
    required this.value,
    required this.fontSize,
  });

  final String label;
  final String value;
  final double fontSize;

  @override
  Widget build(BuildContext context) {
    return Align(
      alignment: Alignment.centerLeft,
      child: Text.rich(
        TextSpan(
          style: TextStyle(
            fontSize: fontSize,
            color: const Color(0xFF111827),
            height: 1.3,
          ),
          children: [
            TextSpan(
              text: '$label: ',
              style: const TextStyle(
                fontWeight: FontWeight.w700,
                color: Color(0xFF4B5563),
              ),
            ),
            TextSpan(text: value),
          ],
        ),
        softWrap: true,
        overflow: TextOverflow.visible,
      ),
    );
  }
}
