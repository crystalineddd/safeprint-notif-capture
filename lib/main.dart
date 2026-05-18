import 'dart:async';
import 'package:cloud_firestore/cloud_firestore.dart';
import 'package:firebase_core/firebase_core.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_svg/flutter_svg.dart';
import 'package:google_fonts/google_fonts.dart';

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  await Firebase.initializeApp();

  runApp(const MyApp());
}

class NotificationCaptureRecord {
  const NotificationCaptureRecord({
    required this.timestamp,
    required this.amount,
    required this.number,
    required this.isParsed,
    required this.rawText,
    required this.packageName,
    required this.title,
    required this.isGcashSource,
  });

  final DateTime timestamp;
  final String amount;
  final String number;
  final bool isParsed;
  final String rawText;
  final String packageName;
  final String title;
  final bool isGcashSource;

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

  Map<String, dynamic> toFirestore() {
    return {
      'amount': amount,
      'number': number,
      'rawText': rawText,
      'capturedAt': FieldValue.serverTimestamp(),
    };
  }

  factory NotificationCaptureRecord.fromMap(Map<dynamic, dynamic> map) {
    final millis =
        (map['timestampEpochMs'] as num?)?.toInt() ??
        DateTime.now().millisecondsSinceEpoch;

    return NotificationCaptureRecord(
      timestamp: DateTime.fromMillisecondsSinceEpoch(millis),
      amount: (map['amount'] as String?)?.trim() ?? '',
      number: (map['number'] as String?)?.trim() ?? '',
      isParsed: (map['isParsed'] as bool?) ?? false,
      rawText: (map['rawText'] as String?)?.trim() ?? '',
      packageName: (map['packageName'] as String?)?.trim() ?? '',
      title: (map['title'] as String?)?.trim() ?? '',
      isGcashSource: (map['isGcashSource'] as bool?) ?? false,
    );
  }
}

class NotificationBridge {
  NotificationBridge._();

  static const MethodChannel _methodChannel = MethodChannel(
    'gcash_capture/methods',
  );
  static const EventChannel _eventChannel = EventChannel(
    'gcash_capture/events',
  );

  static Stream<NotificationCaptureRecord> stream() {
    return _eventChannel.receiveBroadcastStream().map(
      (event) => NotificationCaptureRecord.fromMap(event as Map),
    );
  }

  static Future<void> openNotificationAccessSettings() {
    return _methodChannel.invokeMethod('openNotificationAccessSettings');
  }

  static Future<bool> isNotificationAccessGranted() async {
    final granted = await _methodChannel.invokeMethod<bool>(
      'isNotificationAccessGranted',
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
}

class FirebaseCaptureRepository {
  FirebaseCaptureRepository._();

  static final FirebaseFirestore _db = FirebaseFirestore.instance;

  static Future<void> save(NotificationCaptureRecord record) async {
    await _db.collection('gcash_notifications').add(record.toFirestore());
  }
}

class MyApp extends StatelessWidget {
  const MyApp({super.key});

  static const Color brandColor = Color(0xFFFBB41D);

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'SafePrint',
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
  final List<NotificationCaptureRecord> _captured =
      <NotificationCaptureRecord>[];
  static const double _rowFontSize = 10;

  StreamSubscription<NotificationCaptureRecord>? _subscription;
  bool _hasNotificationAccess = false;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _init();
  }

  Future<void> _init() async {
    await _refreshAccessStatus();

    // Load any notifications that were captured while app was closed
    final saved = await NotificationBridge.loadSavedNotifications();
    for (final record in saved.reversed) {
      if (!_captured.any(
        (r) =>
            r.timestamp.millisecondsSinceEpoch ==
            record.timestamp.millisecondsSinceEpoch,
      )) {
        setState(() {
          _captured.insert(0, record);
        });
      }
    }

    _subscription = NotificationBridge.stream().listen((record) async {
      setState(() {
        _captured.insert(0, record);
      });

      try {
        await FirebaseCaptureRepository.save(record);
      } catch (_) {
        // Keep local capture working even if Firestore write fails.
      }
    }, onError: (Object error) {});
  }

  Future<void> _refreshAccessStatus() async {
    final granted = await NotificationBridge.isNotificationAccessGranted();
    if (!mounted) {
      return;
    }

    setState(() {
      _hasNotificationAccess = granted;
    });
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) {
      _refreshAccessStatus();
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
    _subscription?.cancel();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: Row(
          children: [
            SvgPicture.asset(
              'assets/logo/safeprint_logo.svg',
              width: 22,
              height: 22,
            ),
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
                color: _hasNotificationAccess
                    ? const Color(0xFFEFFAF3)
                    : const Color(0xFFFFF6E0),
                borderRadius: BorderRadius.circular(14),
                border: Border.all(
                  color: _hasNotificationAccess
                      ? const Color(0xFFB7E4C7)
                      : const Color(0xFFF4C152),
                ),
              ),
              child: Text(
                _hasNotificationAccess
                    ? 'Notification access enabled. Waiting for payment alerts.'
                    : 'Notification access is not enabled yet. Tap Open Access Settings and turn SafePrint on.',
                style: const TextStyle(
                  fontSize: 12,
                  height: 1.3,
                  color: Color(0xFF1F2937),
                  fontWeight: FontWeight.w600,
                ),
              ),
            ),
            SizedBox(
              width: double.infinity,
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
            const SizedBox(height: 16),
            Expanded(
              child: _captured.isEmpty
                  ? const Center(child: Text('No payments yet.'))
                  : ListView.separated(
                      itemCount: _captured.length,
                      separatorBuilder: (_, _) => const SizedBox(height: 10),
                      itemBuilder: (context, index) {
                        final item = _captured[index];
                        final amount = item.amount.isEmpty
                            ? '(not parsed)'
                            : item.amount;
                        final number = item.number.isEmpty
                            ? '(not parsed)'
                            : item.number;

                        return Container(
                          decoration: BoxDecoration(
                            color: Colors.white,
                            borderRadius: BorderRadius.circular(14),
                            border: Border.all(color: const Color(0xFFE5E7EB)),
                          ),
                          child: ExpansionTile(
                            tilePadding: const EdgeInsets.symmetric(
                              horizontal: 14,
                              vertical: 4,
                            ),
                            childrenPadding: const EdgeInsets.fromLTRB(
                              14,
                              0,
                              14,
                              14,
                            ),
                            iconColor: const Color(0xFF2563EB),
                            collapsedIconColor: const Color(0xFF2563EB),
                            title: Row(
                              children: [
                                Expanded(
                                  child: Text(
                                    _formatTimestamp(item.timestamp),
                                    overflow: TextOverflow.ellipsis,
                                    style: const TextStyle(
                                      fontSize: _rowFontSize,
                                      fontWeight: FontWeight.w700,
                                      color: Color(0xFF111827),
                                    ),
                                  ),
                                ),
                                const SizedBox(width: 10),
                                Text(
                                  amount,
                                  textAlign: TextAlign.right,
                                  style: const TextStyle(
                                    color: Color(0xFF111827),
                                    fontSize: _rowFontSize,
                                    fontWeight: FontWeight.w700,
                                  ),
                                ),
                              ],
                            ),
                            children: [
                              _DetailRow(
                                label: 'Name',
                                value: item.derivedName,
                                fontSize: _rowFontSize,
                              ),
                              const SizedBox(height: 8),
                              _DetailRow(
                                label: 'Contact Number',
                                value: number,
                                fontSize: _rowFontSize,
                              ),
                            ],
                          ),
                        );
                      },
                    ),
            ),
          ],
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
