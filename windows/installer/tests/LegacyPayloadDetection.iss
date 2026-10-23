#include "../LegacyPayloadDetection.iss"

[Setup]
AppName=ZeroCon legacy payload detection tests
AppVersion=1.0
DefaultDirName={tmp}\ZeroConUnusedTestInstall
PrivilegesRequired=lowest
Uninstallable=no
CreateAppDir=no
OutputBaseFilename=legacy-payload-tests
OutputDir=../../target/installer-tests

[Code]
procedure Check(Condition: Boolean; Name: String);
begin
  if not Condition then
    RaiseException('FAIL: ' + Name);
  Log('PASS: ' + Name);
end;

function InitializeSetup(): Boolean;
var
  Root: String;
  I: Integer;
  Names: TArrayOfString;
begin
  // This harness cancels before installation; all fixtures live in its tmp dir.
  Root := ExpandConstant('{tmp}\legacy-payload-fixture');
  Check(not PreviousInnoPayloadIsAbsent(Root), 'missing root is not proof of absence');
  Check(ForceDirectories(Root), 'create isolated fixture');
  Check(SaveStringToFile(Root + '\settings.json', '{}', False), 'preserved settings fixture');
  Check(SaveStringToFile(Root + '\unins000.exe', 'unknown uninstaller: do not execute', False), 'unknown uninstaller fixture');
  Check(SaveStringToFile(Root + '\unins000.dat', 'remaining data', False), 'uninstall data fixture');
  Check(ForceDirectories(Root + '\LearningMemory'), 'preserved learning directory fixture');
  Check(SaveStringToFile(Root + '\azookey.dll.old', 'old backup', False), 'backup DLL fixture');
  Check(not PreviousInnoPayloadIsAbsent(Root + '\settings.json'), 'file path is not an inspectable install root');
  Check(PreviousInnoPayloadIsAbsent(Root), 'uninstalled product with stale uninstaller is skipped');
  SetArrayLength(Names, 9);
  Names[0] := 'azookey.dll';
  Names[1] := 'azookey32.dll';
  Names[2] := 'azookey_windows.dll';
  Names[3] := 'frontend.exe';
  Names[4] := 'azookey-updater-helper.exe';
  Names[5] := 'launcher.exe';
  Names[6] := 'azookey-server.exe';
  Names[7] := 'azookey-server.dll';
  Names[8] := 'ui.exe';
  for I := 0 to GetArrayLength(Names) - 1 do
  begin
    Check(SaveStringToFile(Root + '\' + Names[I], 'remaining payload', False), 'create ' + Names[I]);
    Check(not PreviousInnoPayloadIsAbsent(Root), 'remaining ' + Names[I] + ' still requires trust validation');
    Check(DeleteFile(Root + '\' + Names[I]), 'remove isolated fixture ' + Names[I]);
  end;
  Check(CreateDir(Root + '\AZOOKEY.DLL'), 'core-name directory fixture');
  Check(not PreviousInnoPayloadIsAbsent(Root), 'case-insensitive core-name directory blocks skip');
  Check(FileExists(Root + '\settings.json') and FileExists(Root + '\unins000.exe') and
    FileExists(Root + '\unins000.dat') and FileExists(Root + '\azookey.dll.old') and
    DirExists(Root + '\LearningMemory'), 'settings, learning, backups and untrusted uninstaller are preserved');
  Log('ALL LEGACY PAYLOAD TESTS PASSED');
  Result := False;
end;
