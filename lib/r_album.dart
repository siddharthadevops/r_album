// Copyright 2020 The rhyme_lph Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

import 'dart:async';

import 'package:flutter/services.dart';

class RAlbum {
  static const MethodChannel _channel =
      const MethodChannel('com.rhyme_lph/r_album');

  /// create one album
  static Future<bool?> createAlbum(String albumName) async {
    return await _channel.invokeMethod('createAlbum', {
      'albumName': albumName,
    });
  }

  /// save files in album
  static Future<bool?> saveAlbum(
      String albumName, List<String> filePaths) async {
    return await _channel.invokeMethod('saveAlbum', {
      'albumName': albumName,
      'filePaths': filePaths,
    });
  }
  
  /// save files in album with location data
  static Future<bool?> saveAlbumWithLocation(
      String albumName, List<String> filePaths, {double? latitude, double? longitude}) async {
    return await _channel.invokeMethod('saveAlbumWithLocation', {
      'albumName': albumName,
      'filePaths': filePaths,
      'latitude': latitude,
      'longitude': longitude,
    });
  }
}
