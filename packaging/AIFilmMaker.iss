; Bộ cài AI Film Maker (Inno Setup 6.3+). Lưu file này dạng UTF-8 có BOM.
; Biên dịch: build_release.bat gọi ISCC.exe sau khi đã tạo build\image\AIFilmMaker.

#define AppName "AI Film Maker"
#ifndef AppVersion
  #define AppVersion "1.0.0"
#endif
#define AppExe "AIFilmMaker.exe"
; build_release.bat truyền /DImageDir (thư mục app image) và /DIconFile; giá trị dưới là mặc định khi biên dịch tay
#ifndef ImageDir
  #define ImageDir "C:\afm-build\image\AIFilmMaker"
#endif
#ifndef IconFile
  #define IconFile "C:\afm-build\app.ico"
#endif

[Setup]
; GIỮ NGUYÊN AppId qua mọi phiên bản để bản mới cài đè lên bản cũ
AppId={{5E0B1C42-7A9D-4F36-B8E1-2C4D6A9F0B73}
AppName={#AppName}
AppVersion={#AppVersion}
AppPublisher=AI Film Maker
; Thư mục cài phải CHỈ GỒM KÝ TỰ ASCII: launcher của jpackage (JDK 17) không khởi động được JVM khi đường dẫn có
; chữ có dấu (ví dụ tên người dùng Windows "Nguyễn"). Vì vậy không dùng {localappdata}\Programs mà dùng C:\AIFilmMaker.
DefaultDirName={sd}\AIFilmMaker
; Không cần quyền admin (người dùng thường được tạo thư mục ở gốc ổ C:)
PrivilegesRequired=lowest
DisableProgramGroupPage=yes
OutputBaseFilename=AIFilmMaker-Setup-{#AppVersion}
#if FileExists(IconFile)
SetupIconFile={#IconFile}
#endif
UninstallDisplayIcon={app}\{#AppExe}
Compression=lzma2/ultra64
SolidCompression=yes
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
CloseApplications=force
WizardStyle=modern

[Tasks]
Name: "desktopicon"; Description: "Tạo biểu tượng ngoài Desktop"

[Files]
Source: "{#ImageDir}\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
Name: "{autoprograms}\{#AppName}"; Filename: "{app}\{#AppExe}"
Name: "{autodesktop}\{#AppName}"; Filename: "{app}\{#AppExe}"; Tasks: desktopicon

[Run]
Filename: "{app}\{#AppExe}"; Description: "Mở {#AppName}"; Flags: nowait postinstall skipifsilent

[UninstallRun]
; Tắt cả cây tiến trình: Java → Python worker → Edge do Playwright mở
Filename: "{sys}\taskkill.exe"; Parameters: "/F /T /IM {#AppExe}"; Flags: runhidden; RunOnceId: "KillApp"

; Dữ liệu người dùng (%LOCALAPPDATA%\AIFilmMaker: DB, profile tài khoản, file chờ duyệt) KHÔNG bị xóa khi gỡ cài đặt.

[Code]
function IsAsciiPath(const S: String): Boolean;
var
  I: Integer;
begin
  Result := True;
  for I := 1 to Length(S) do
    if Ord(S[I]) > 127 then
    begin
      Result := False;
      Exit;
    end;
end;

function NextButtonClick(CurPageID: Integer): Boolean;
begin
  Result := True;
  if (CurPageID = wpSelectDir) and not IsAsciiPath(WizardDirValue) then
  begin
    MsgBox('Thư mục cài đặt không được chứa chữ có dấu (giới hạn của Java).' + #13#10 +
           'Hãy chọn thư mục khác, ví dụ C:\AIFilmMaker.', mbError, MB_OK);
    Result := False;
  end;
end;
