# IR_Khatary DNS — Windows 10/11
# Real Windows DNS client settings only; this is not a VPN.
# Run PowerShell as Administrator.
Add-Type -AssemblyName PresentationFramework
$ErrorActionPreference = 'Stop'
$window = New-Object Windows.Window
$window.Title = 'IR_Khatary DNS'
$window.Width = 470
$window.Height = 360
$window.WindowStartupLocation = 'CenterScreen'
$window.Background = [Windows.Media.Brushes]::new('#0B1020')
$window.Foreground = [Windows.Media.Brushes]::White
$panel = New-Object Windows.Controls.StackPanel
$panel.Margin = '22'
$window.Content = $panel

$title = New-Object Windows.Controls.TextBlock
$title.Text = 'IR_Khatary DNS'
$title.FontSize = 27
$title.FontWeight = 'Bold'
$title.Foreground = [Windows.Media.Brushes]::new('#69D2FF')
$title.Margin = '0,0,0,8'
$panel.Children.Add($title) | Out-Null

$desc = New-Object Windows.Controls.TextBlock
$desc.Text = 'Windows 10 / 11 • Real system DNS'
$desc.Margin = '0,0,0,16'
$panel.Children.Add($desc) | Out-Null

$label = New-Object Windows.Controls.TextBlock
$label.Text = 'Network adapter'
$label.Margin = '0,0,0,5'
$panel.Children.Add($label) | Out-Null
$adapters = New-Object Windows.Controls.ComboBox
$adapters.Height = 34
$adapters.Margin = '0,0,0,14'
Get-NetAdapter | Where-Object { $_.Status -eq 'Up' } | ForEach-Object {
    $item = New-Object Windows.Controls.ComboBoxItem
    $item.Content = $_.Name
    $item.Tag = $_.Name
    $adapters.Items.Add($item) | Out-Null
}
if ($adapters.Items.Count -gt 0) { $adapters.SelectedIndex = 0 }
$panel.Children.Add($adapters) | Out-Null

$dns = New-Object Windows.Controls.ComboBox
$dns.Height = 34
$dns.Margin = '0,0,0,16'
@(
    @{ Label='Cloudflare (1.1.1.1)'; Servers=@('1.1.1.1','1.0.0.1') },
    @{ Label='Google (8.8.8.8)'; Servers=@('8.8.8.8','8.8.4.4') },
    @{ Label='Reset to automatic DNS (DHCP)'; Servers=@() }
) | ForEach-Object {
    $item = New-Object Windows.Controls.ComboBoxItem
    $item.Content = $_.Label
    $item.Tag = $_.Servers
    $dns.Items.Add($item) | Out-Null
}
$dns.SelectedIndex = 0
$panel.Children.Add($dns) | Out-Null

$status = New-Object Windows.Controls.TextBlock
$status.Text = 'Status: no changes applied yet.'
$status.TextWrapping = 'Wrap'
$status.Margin = '0,0,0,14'
$panel.Children.Add($status) | Out-Null

$apply = New-Object Windows.Controls.Button
$apply.Content = 'Apply DNS setting'
$apply.Height = 40
$apply.Margin = '0,0,0,8'
$apply.Background = [Windows.Media.Brushes]::new('#147DAB')
$apply.Foreground = [Windows.Media.Brushes]::White
$apply.Add_Click({
    try {
        if (-not $adapters.SelectedItem) { throw 'Select an active network adapter first.' }
        $name = [string]$adapters.SelectedItem.Tag
        $servers = @($dns.SelectedItem.Tag)
        if ($servers.Count -eq 0 -or [string]::IsNullOrWhiteSpace([string]$servers[0])) {
            Set-DnsClientServerAddress -InterfaceAlias $name -ResetServerAddresses
            $status.Text = "Automatic DHCP DNS requested for $name."
        } else {
            Set-DnsClientServerAddress -InterfaceAlias $name -ServerAddresses $servers
            Clear-DnsClientCache
            $status.Text = "DNS applied to ${name}: $($servers -join ', '). This is DNS, not VPN."
        }
    } catch {
        $status.Text = "Error: $($_.Exception.Message). Run PowerShell as Administrator."
    }
})
$panel.Children.Add($apply) | Out-Null

$note = New-Object Windows.Controls.TextBlock
$note.Text = 'DNS changes do not guarantee lower ping or bypass blocking. Choose DHCP to restore automatic DNS.'
$note.TextWrapping = 'Wrap'
$note.Foreground = [Windows.Media.Brushes]::new('#B8C4D8')
$panel.Children.Add($note) | Out-Null
$window.ShowDialog() | Out-Null
