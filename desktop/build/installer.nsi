; Kororythm for Windows installer (NSIS, Modern UI 2).
; Build: makensis -DVERSION=2.0.0 -DEXE=path\to\kororythm.exe -DOUT=Kororythm-Setup.exe installer.nsi
; Installs for the current user only, so no administrator rights are needed.

Unicode true
!include "MUI2.nsh"
!include "LogicLib.nsh"

!ifndef VERSION
  !define VERSION "2.0.0"
!endif
!ifndef EXE
  !define EXE "..\bin\kororythm.exe"
!endif
!ifndef OUT
  !define OUT "Kororythm-${VERSION}-Setup.exe"
!endif

Name "Kororythm"
OutFile "${OUT}"
InstallDir "$LOCALAPPDATA\Programs\Kororythm"
InstallDirRegKey HKCU "Software\Kororythm" "InstallDir"
RequestExecutionLevel user
SetCompressor /SOLID lzma
BrandingText "Kororythm ${VERSION}"

VIProductVersion "${VERSION}.0"
VIAddVersionKey "ProductName" "Kororythm"
VIAddVersionKey "FileDescription" "Kororythm installer"
VIAddVersionKey "FileVersion" "${VERSION}"
VIAddVersionKey "ProductVersion" "${VERSION}"
VIAddVersionKey "LegalCopyright" "GPL-3.0"

!define MUI_ICON "appicon.ico"
!define MUI_UNICON "appicon.ico"
!define MUI_ABORTWARNING
!define MUI_WELCOMEPAGE_TEXT "This will install Kororythm ${VERSION}, a FLAC and MP3 player with a Winamp-style layout.$\r$\n$\r$\nYour music stays where it is: Kororythm only reads the folders you choose."
!define MUI_FINISHPAGE_RUN "$INSTDIR\kororythm.exe"
!define MUI_FINISHPAGE_RUN_TEXT "Start Kororythm"

!insertmacro MUI_PAGE_WELCOME
!insertmacro MUI_PAGE_LICENSE "..\..\LICENSE"
!insertmacro MUI_PAGE_DIRECTORY
!insertmacro MUI_PAGE_COMPONENTS
!insertmacro MUI_PAGE_INSTFILES
!insertmacro MUI_PAGE_FINISH
!insertmacro MUI_UNPAGE_CONFIRM
!insertmacro MUI_UNPAGE_INSTFILES
!insertmacro MUI_LANGUAGE "English"
!insertmacro MUI_LANGUAGE "Spanish"

!define UNINST "Software\Microsoft\Windows\CurrentVersion\Uninstall\Kororythm"

Function .onInit
  ; Kororythm draws its windows with Microsoft Edge WebView2, built into Windows 11
  ; and most Windows 10 PCs. Warn if it is missing.
  ReadRegStr $0 HKLM "SOFTWARE\WOW6432Node\Microsoft\EdgeUpdate\Clients\{F3017226-FE2A-4295-8BDF-00C3A9A7E4C5}" "pv"
  ${If} $0 == ""
    ReadRegStr $0 HKCU "Software\Microsoft\EdgeUpdate\Clients\{F3017226-FE2A-4295-8BDF-00C3A9A7E4C5}" "pv"
  ${EndIf}
  ${If} $0 == ""
    MessageBox MB_ICONINFORMATION|MB_OK "Kororythm needs the Microsoft Edge WebView2 Runtime, which this PC doesn't seem to have.$\r$\n$\r$\nInstall it from Microsoft (search for 'WebView2 Runtime download'), then start Kororythm."
  ${EndIf}
FunctionEnd

Section "Kororythm (required)" SecMain
  SectionIn RO
  SetOutPath "$INSTDIR"
  ; Close a running copy so the file can be replaced.
  nsExec::Exec 'taskkill /IM kororythm.exe /F'
  File "/oname=kororythm.exe" "${EXE}"
  File "appicon.ico"
  File "/oname=LICENSE.txt" "..\..\LICENSE"
  WriteUninstaller "$INSTDIR\Uninstall Kororythm.exe"
  CreateDirectory "$SMPROGRAMS\Kororythm"
  CreateShortcut "$SMPROGRAMS\Kororythm\Kororythm.lnk" "$INSTDIR\kororythm.exe" "" "$INSTDIR\appicon.ico"
  CreateShortcut "$SMPROGRAMS\Kororythm\Uninstall Kororythm.lnk" "$INSTDIR\Uninstall Kororythm.exe"
  WriteRegStr HKCU "Software\Kororythm" "InstallDir" "$INSTDIR"
  WriteRegStr HKCU "${UNINST}" "DisplayName" "Kororythm"
  WriteRegStr HKCU "${UNINST}" "DisplayVersion" "${VERSION}"
  WriteRegStr HKCU "${UNINST}" "Publisher" "Jose Acosta Hernandez"
  WriteRegStr HKCU "${UNINST}" "DisplayIcon" "$INSTDIR\appicon.ico"
  WriteRegStr HKCU "${UNINST}" "UninstallString" '"$INSTDIR\Uninstall Kororythm.exe"'
  WriteRegStr HKCU "${UNINST}" "InstallLocation" "$INSTDIR"
  WriteRegDWORD HKCU "${UNINST}" "NoModify" 1
  WriteRegDWORD HKCU "${UNINST}" "NoRepair" 1
SectionEnd

Section "Desktop shortcut" SecDesktop
  CreateShortcut "$DESKTOP\Kororythm.lnk" "$INSTDIR\kororythm.exe" "" "$INSTDIR\appicon.ico"
SectionEnd

Section "Uninstall"
  nsExec::Exec 'taskkill /IM kororythm.exe /F'
  Delete "$INSTDIR\kororythm.exe"
  Delete "$INSTDIR\appicon.ico"
  Delete "$INSTDIR\LICENSE.txt"
  Delete "$INSTDIR\Uninstall Kororythm.exe"
  RMDir "$INSTDIR"
  Delete "$SMPROGRAMS\Kororythm\Kororythm.lnk"
  Delete "$SMPROGRAMS\Kororythm\Uninstall Kororythm.lnk"
  RMDir "$SMPROGRAMS\Kororythm"
  Delete "$DESKTOP\Kororythm.lnk"
  DeleteRegKey HKCU "${UNINST}"
  DeleteRegKey HKCU "Software\Kororythm"
  ; Your settings and library list in %APPDATA%\Kororythm are kept; your music is never touched.
SectionEnd
